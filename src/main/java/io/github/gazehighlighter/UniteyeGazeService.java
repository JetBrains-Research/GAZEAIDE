package io.github.gazehighlighter;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.cef.CefApp;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefMediaAccessCallback;
import org.cef.handler.CefLoadHandler.ErrorCode;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefPermissionHandler;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.Point;
import java.awt.GraphicsEnvironment;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import java.awt.MouseInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import javax.swing.Timer;

/**
 * Owns the embedded-browser (JCEF) instance that runs UnitEye's browser gaze pipeline
 * (webcam -> MediaPipe FaceLandmarker -> EyeMU, https://github.com/wgnrto/uniteye, GPL-3.0) and
 * feeds its gaze stream into the per-project {@link GazeDispatcher}s.
 *
 * <p>Lifecycle is driven by {@link GazeInputSettings#getInputMode()}: the browser + local HTTP
 * server run while the mode is {@link GazeInputMode#WEBCAM} and are torn down otherwise. If the
 * tracker cannot start (JCEF unsupported, camera unavailable, model/CDN load failure) or dies
 * unexpectedly, the service notifies the user and falls back to {@link GazeInputMode#MOUSE}.
 *
 * <p>{@code getUserMedia} needs a real HTTP origin (loopback counts as a secure context; {@code
 * file://}/{@code data:} do not), so the vendored web assets under {@code gaze/web/} are served
 * from a tiny loopback-only HTTP server rather than loaded directly. The browser uses windowless off-screen rendering. No native top-level
 * window is created. Sampling uses a sequential timer rather than paint-dependent animation frames.
 *
 * @see #WEB_ROOT_RESOURCE the bundled web pipeline in resources/gaze/web
 */
@Service
public final class UniteyeGazeService implements Disposable {

    private static final Logger LOG = Logger.getInstance(UniteyeGazeService.class);
    private static final String WEB_ROOT_RESOURCE = "/gaze/web";
    private static final String[] WEB_ASSETS = {
            "bridge.html", "uniteye-core.js", "uniteye-cv.js", "models/eyemu.onnx"
    };

    private volatile boolean running;
    private volatile boolean disposed;
    // Browser lifecycle and protocol handling are confined to the EDT. No monitor is held
    // across UI dispatch: shutdown cannot deadlock a background start waiting for the EDT.
    private volatile boolean starting;
    private volatile String statusMessage = "Tracker not started.";
    private volatile long lifecycleGeneration;

    private @Nullable Path webRoot;
    private @Nullable HttpServer httpServer;
    private @Nullable JBCefBrowser browser;
    private @Nullable JBCefJSQuery jsQuery;
    private @Nullable CefApp browserApp;
    private @Nullable GazeTrackerStartup startup;

    // ── Coalescing of gaze samples onto the EDT (cap at one pending routing) ──
    private volatile int     latestX;
    private volatile int     latestY;
    private volatile boolean routePending;
    private volatile boolean trackingValid;
    private volatile long lastGoodMs, routeGeneration;
    private final Timer validityTimer = new Timer(250, e -> {
        checkStartup();
        if (running && trackingValid && System.currentTimeMillis() - lastGoodMs > 1000) {
            statusMessage = "No recent valid gaze sample. Check face framing and lighting."; invalidateTracking();
        }
    });
    private final Timer simulationTimer = new Timer(50, e -> simulatePointer());

    private final GazePointFilter pointFilter = new GazePointFilter();
    public void resetFilter() {
        onEdt(() -> { pointFilter.reset(); ++routeGeneration; GazeDispatcher.suspendAllProjects(false); });
    }

    // ── Raw (pre-calibration, pre-smoothing) sample tap, used by GazeCalibrationDialog ──
    private volatile @Nullable Consumer<Point> rawSampleListener;

    /** Register/clear a listener that sees every raw "OK x y" sample before calibration or
     *  smoothing is applied. Used by calibration and personalization to collect guided-target samples. */
    public void setRawSampleListener(@Nullable Consumer<Point> listener) {
        onEdt(() -> {
            this.rawSampleListener = listener;
            resetFilter();
        });
    }

    public static UniteyeGazeService getInstance() {
        return ApplicationManager.getApplication().getService(UniteyeGazeService.class);
    }

    // ── Public lifecycle ──────────────────────────────────────────────────────
    //
    // File extraction/server preparation run in the pool; browser ownership stays on the EDT.
    // Generations reject late callbacks and resources from interrupted starts/restarts.

    /** Start or stop the tracker to match the requested input mode. */
    public void applyMode(GazeInputMode mode) {
        onEdt(() -> {
            if (disposed) return;
            if (mode == GazeInputMode.WEBCAM) {
                start();
            } else {
                stop();
            }
        });
    }

    /** Restart the tracker (e.g. after options changed or accuracy drifted). */
    public void restart() {
        onEdt(() -> {
            if (disposed) return;
            stop();          // fully terminates the old session before the new one grabs the camera
            start();
        });
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isTrackingValid() { return trackingValid; }
    public boolean isStarting() { return starting; }
    public String getStatusMessage() { return statusMessage; }

    @Override
    public void dispose() {
        disposed = true;
        if (SwingUtilities.isEventDispatchThread()) stop();
        else ApplicationManager.getApplication().invokeAndWait(this::stop);
    }

    private static void onEdt(Runnable body) {
        if (SwingUtilities.isEventDispatchThread()) body.run();
        else SwingUtilities.invokeLater(body);
    }

    // ── Start / stop ────────────────────────────────────────────────────────────

    private void start() {
        if (disposed || running || starting) return;
        start(new GazeTrackerStartup(monotonicMs()));
    }

    private void start(GazeTrackerStartup progress) {
        starting = true;
        startup = progress;
        statusMessage = progress.message();
        LOG.info("Webcam startup: " + statusMessage);
        validityTimer.start();
        long generation = ++lifecycleGeneration;
        pointFilter.reset();
        lastGoodMs = 0;
        if (GazeInputSettings.getInstance().isMock()) {
            statusMessage = "Mouse simulation active; no eye tracking.";
            startup = null;
            starting = false; running = true;
            simulationTimer.start(); validityTimer.start();
            notifyUser("Mouse simulation is active (no camera or eye tracking).", NotificationType.INFORMATION);
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Path root = null;
            HttpServer server = null;
            try {
                root = ensureWebRoot();
                server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
                server.createContext("/", new StaticFileHandler(root));
                server.setExecutor(null);
                server.start();
                Path preparedRoot = root;
                HttpServer preparedServer = server;
                onEdt(() -> finishStart(generation, preparedRoot, preparedServer));
            } catch (Exception e) {
                cleanupServer(server, root);
                onEdt(() -> {
                    if (!disposed && generation == lifecycleGeneration)
                        fallbackToMouse("Could not prepare the gaze pipeline: " + e.getMessage());
                });
            }
        });
    }

    private void finishStart(long generation, Path root, HttpServer server) {
        if (disposed || generation != lifecycleGeneration) {
            cleanupServer(server, root); return;
        }
        webRoot = root; httpServer = server;
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        var screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        String url = base + "bridge.html?screenW=" + screen.width + "&screenH=" + screen.height
                + "&screenX=" + screen.x + "&screenY=" + screen.y;
        try {
            advanceStartup(GazeTrackerStartup.Stage.BROWSER);
            String error = startBrowser(base, url, generation);
            if (error != null) { fallbackToMouse(error); return; }
            // Native creation is asynchronous. Neither a Java browser wrapper nor createImmediately()
            // proves that Chromium, models or the camera are ready. READY arrives over the bridge.
        } catch (Exception e) {
            fallbackToMouse("Could not start the embedded browser: " + e.getMessage());
        }
    }

    /** Must run on the EDT: creates a windowless JCEF browser and wires handlers before creation.
     *  Returns an error message on (synchronous) failure, or null on success. */
    private @Nullable String startBrowser(String originPrefix, String url, long generation) {
        if (!JBCefApp.isSupported()) {
            return "The embedded browser (JCEF) isn't available in this IDE/JBR.";
        }

        if (!JBCefApp.isOffScreenRenderingModeEnabled()) return "Windowless JCEF is disabled in this IDE. Enable ide.browser.jcef.osr.enabled and restart the IDE, or use Mouse proxy.";

        JBCefBrowser b;
        try {
            JBCefApp.getInstance();
            // IntelliJ can replace CefApp after a failed startup test. Browsers created against
            // the old app do not migrate: retain its identity to recreate only our browser.
            browserApp = CefApp.getInstance();
            b = JBCefBrowser.createBuilder().setOffScreenRendering(true).setCreateImmediately(false).setUrl(url).build();
            b.getComponent().setSize(640, 480);
            b.getComponent().doLayout();
        } catch (Exception e) {
            return "Could not create the embedded browser: " + e.getMessage();
        }
        browser = b;

        // Auto-grant camera access, but only to the page we ourselves are serving.
        b.getJBCefClient().addPermissionHandler(new CefPermissionHandler() {
            @Override
            public boolean onRequestMediaAccessPermission(CefBrowser cefBrowser, CefFrame frame,
                    String requestingUrl, int requestedPermissions, CefMediaAccessCallback callback) {
                if (requestingUrl != null && requestingUrl.startsWith(originPrefix)) {
                    callback.Continue(requestedPermissions);
                } else {
                    callback.Cancel();
                }
                return true;
            }
        }, b.getCefBrowser());

        JBCefJSQuery query = JBCefJSQuery.create(b);
        query.addHandler(line -> {
            onEdt(() -> {
                if (!disposed && generation == lifecycleGeneration) handleLine(line);
            });
            return new JBCefJSQuery.Response(null);
        });
        jsQuery = query;

        b.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (!frame.isMain() || disposed || generation != lifecycleGeneration) return;
                if (httpStatusCode >= 400) {
                    onEdt(() -> {
                        if (!disposed && generation == lifecycleGeneration)
                            fallbackToMouse("The local gaze bridge returned HTTP " + httpStatusCode + ". Retry the webcam connection.");
                    });
                    return;
                }
                String js = "window.implicitBridge = function(msg) {" + query.inject("msg") + "};"
                        + "if (window.__implicitQueue) {"
                        + "  window.__implicitQueue.forEach(window.implicitBridge);"
                        + "  window.__implicitQueue = null;"
                        + "}";
                frame.executeJavaScript(js, frame.getURL(), 0);
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, ErrorCode errorCode,
                    String errorText, String failedUrl) {
                if (frame.isMain()) {
                    onEdt(() -> {
                        if (!disposed && generation == lifecycleGeneration)
                            fallbackToMouse("The gaze bridge page failed to load: " + errorText);
                    });
                }
            }
        }, b.getCefBrowser());

        b.getJBCefClient().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override public void onAfterCreated(CefBrowser cefBrowser) {
                if (disposed || generation != lifecycleGeneration) return;
                // Realize a windowless surface, never a Swing/native top-level host.
                cefBrowser.wasResized(640, 480);
                cefBrowser.setWindowVisibility(true);
                onEdt(() -> {
                    if (!disposed && generation == lifecycleGeneration) advanceStartup(GazeTrackerStartup.Stage.PAGE);
                });
            }
        }, b.getCefBrowser());
        b.createImmediately();
        return null;
    }

    private static long monotonicMs() { return System.nanoTime() / 1_000_000; }

    private void advanceStartup(GazeTrackerStartup.Stage stage) {
        if (startup != null && startup.advance(stage, monotonicMs())) {
            statusMessage = startup.message();
            LOG.info("Webcam startup: " + statusMessage);
        }
    }

    private void checkStartup() {
        if (disposed || startup == null) return;
        boolean replaced;
        try { replaced = browserApp != null && CefApp.getInstance() != browserApp; }
        catch (IllegalStateException e) {
            fallbackToMouse("IntelliJ's embedded browser shut down. Restart the IDE, then reconnect the webcam.");
            return;
        }
        if (replaced) {
            GazeTrackerStartup progress = startup;
            if (!progress.recoverBrowser(monotonicMs())) {
                fallbackToMouse("IntelliJ restarted its embedded browser again. Restart the IDE, then reconnect the webcam. Check idea.log for JCEF errors.");
                return;
            }
            LOG.warn("IntelliJ replaced JCEF; reconnecting the webcam once against the new browser runtime.");
            stop();
            start(progress);
            notifyUser("IntelliJ restarted its embedded browser. Reconnecting the webcam; any calibration in progress was cancelled. Retry calibration once gaze is ready.", NotificationType.INFORMATION);
            return;
        }
        String failure = startup.timeout(monotonicMs());
        if (failure != null) fallbackToMouse(failure);
    }

    private void stop() {
        GazeCalibrationDialog.cancelActive();
        ++lifecycleGeneration;
        invalidateTracking();
        validityTimer.stop();
        simulationTimer.stop();
        running = false; starting = false;
        startup = null; browserApp = null;
        rawSampleListener = null;

        JBCefBrowser b = browser;
        browser = null;
        JBCefJSQuery query = jsQuery;
        jsQuery = null;
        // A replaced/terminated native app must not prevent release of the loopback server
        // or the next connection. Dispose the two browser resources independently.
        if (query != null) {
            try { query.dispose(); }
            catch (RuntimeException e) { LOG.warn("Could not dispose the old webcam JS bridge", e); }
        }
        if (b != null) {
            try { b.dispose(); }
            catch (RuntimeException e) { LOG.warn("Could not dispose the old webcam browser", e); }
        }

        HttpServer server = httpServer;
        httpServer = null;
        Path root = webRoot;
        webRoot = null;
        cleanupServer(server, root);
    }

    private static void cleanupServer(@Nullable HttpServer server, @Nullable Path root) {
        if (server != null) server.stop(0);
        if (root != null) deleteRecursively(root);
    }

    private static void deleteRecursively(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // best-effort cleanup; the JVM-exit deleteOnExit() on the root is the fallback
        }
    }

    // ── JS -> Java gaze protocol (mirrors the plain-text contract the old sidecar used) ──

    private void simulatePointer() {
        if (!running || disposed) return;
        var pointer = MouseInfo.getPointerInfo();
        if (pointer == null) { invalidateTracking(); return; }
        Point p = pointer.getLocation();
        trackingValid = true; lastGoodMs = System.currentTimeMillis();
        queueRoute(p.x, p.y);
    }

    private void handleLine(String line) {
        if (line == null) return;
        line = line.trim();
        if (line.isEmpty()) return;

        if (line.startsWith("OK ")) {
            String[] parts = line.split("\\s+");
            if (parts.length >= 3) {
                try {
                    int rawX = Integer.parseInt(parts[1]);
                    int rawY = Integer.parseInt(parts[2]);

                    if ((!running && !starting) || GazeInputSettings.getInstance().getInputMode() != GazeInputMode.WEBCAM) return;
                    advanceStartup(GazeTrackerStartup.Stage.READY);
                    starting = false; running = true;
                    trackingValid = true;
                    statusMessage = "Valid gaze samples received.";
                    lastGoodMs = System.currentTimeMillis();
                    Consumer<Point> tap = rawSampleListener;
                    if (tap != null) { tap.accept(new Point(rawX, rawY)); return; }
                    GazeInputSettings settings = GazeInputSettings.getInstance();
                    GazeCalibration calibration = settings.getCalibration();
                    Point filtered = pointFilter.update(calibration.x(rawX, rawY), calibration.y(rawX, rawY),
                            System.nanoTime() / 1_000_000, settings.getResponse().smoothingMs);
                    if (filtered != null) queueRoute(filtered.x, filtered.y);
                } catch (NumberFormatException ignored) { /* skip malformed sample */ }
            }
        } else if (line.equals("READY")) {
            advanceStartup(GazeTrackerStartup.Stage.READY);
            starting = false; running = true;
            statusMessage = startup == null ? "Camera ready; waiting for valid gaze samples." : startup.message();
            notifyUser("Webcam gaze tracking is active.", NotificationType.INFORMATION);
        } else if (line.startsWith("STATUS ")) {
            try { advanceStartup(GazeTrackerStartup.Stage.valueOf(line.substring(7))); }
            catch (IllegalArgumentException ignored) { /* ignore unknown protocol stages */ }
        } else if (line.startsWith("ERR ")) {
            fallbackToMouse(line.substring(4));
        } else if (line.equals("BAD")) {
            statusMessage = "Eyes closed or face unavailable. Tracking pauses until valid samples return.";
            invalidateTracking();
        }
    }

    private void invalidateTracking() {
        pointFilter.reset();
        if (!trackingValid) return;
        trackingValid = false; ++routeGeneration;
        GazeDispatcher.suspendAllProjects(false);
    }

    private void queueRoute(int x, int y) {
        latestX = x;
        latestY = y;
        if (routePending) return;
        routePending = true;
        final long generation = routeGeneration;
        ApplicationManager.getApplication().invokeLater(() -> {
            routePending = false;
            if (!running || !trackingValid || rawSampleListener != null || generation != routeGeneration) return;
            routeToProjects(latestX, latestY);
        });
    }

    private void routeToProjects(int x, int y) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            GazeDispatcher dispatcher = GazeDispatcher.getInstance(project);
            if (dispatcher != null) dispatcher.routeScreenPoint(x, y);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Extract the bundled web pipeline to a temp directory once per session. */
    private Path ensureWebRoot() throws IOException {
        Path dir = Files.createTempDirectory("implicit-gaze-web");
        try {
        for (String asset : WEB_ASSETS) {
            Path dest = dir.resolve(asset);
            Files.createDirectories(dest.getParent());
            try (InputStream in = UniteyeGazeService.class.getResourceAsStream(WEB_ROOT_RESOURCE + "/" + asset)) {
                if (in == null) throw new IOException("bundled resource " + asset + " not found");
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        dir.toFile().deleteOnExit();
        return dir;
        } catch (IOException e) {
            deleteRecursively(dir); throw e;
        }
    }

    /** Show the message, flip the persisted mode back to MOUSE, and tear the tracker down. */
    private void fallbackToMouse(String message) {
        LOG.warn("Webcam gaze unavailable: " + message);
        GazeDispatcher.suspendAllProjects(true);
        GazeInputSettings.getInstance().setInputMode(GazeInputMode.MOUSE);
        statusMessage = "Returned to mouse proxy: " + message;
        notifyUser("Webcam gaze unavailable — falling back to mouse. " + message,
                NotificationType.WARNING);
        stop();
        GazeRecordingService.configurationChangedForOpenProjects();
    }

    private void notifyUser(String content, NotificationType type) {
        if (type == NotificationType.WARNING) GazeNotify.warn(null, content);
        else GazeNotify.info(null, content);
    }

    // ── Static file server for the vendored web pipeline (loopback only) ──────────

    private static final class StaticFileHandler implements HttpHandler {
        private final Path root;

        StaticFileHandler(Path root) {
            this.root = root;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/") || path.isEmpty()) path = "/bridge.html";

                Path resolved = root.resolve(path.substring(1)).normalize();
                if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
                    byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, body.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
                    return;
                }

                byte[] bytes = Files.readAllBytes(resolved);
                exchange.getResponseHeaders().set("Content-Type", contentType(resolved.toString()));
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } finally {
                exchange.close();
            }
        }

        private static String contentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
            if (path.endsWith(".onnx")) return "application/octet-stream";
            return "application/octet-stream";
        }
    }
}
