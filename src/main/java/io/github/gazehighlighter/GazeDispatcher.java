package io.github.gazehighlighter;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-scoped registry that owns one {@link GazeEngine} per open {@link Editor} and routes
 * gaze positions to the right engine.
 *
 * <p>The mouse source already knows its editor, so it talks to {@link #engineFor(Editor)}
 * directly. The webcam source ({@link UniteyeGazeService}) only knows a <i>screen</i> pixel
 * coordinate, so it calls {@link #routeScreenPoint(int, int)}, which finds the editor under
 * that point, converts it to a logical line/column, and feeds the engine.
 */
@Service(Service.Level.PROJECT)
public final class GazeDispatcher {

    private final Map<Editor, GazeEngine> engines = new ConcurrentHashMap<>();

    /** The editor that most recently received a routed gaze point (for onExit on leave). */
    private volatile @Nullable Editor lastRoutedEditor;
    private final GazeLineTarget lineTarget = new GazeLineTarget();
    private Rectangle lastVisible;
    private Point lastOrigin;
    private long lastStamp;
    private int lastLineHeight, lastWrapCount, lastFoldHash;

    public static GazeDispatcher getInstance(Project project) {
        return project.getService(GazeDispatcher.class);
    }

    // ── Registry (called by the startup activity per editor) ──────────────────

    public GazeEngine register(Editor editor, Project project) {
        return engines.computeIfAbsent(editor, e -> new GazeEngine(e, project));
    }

    public void unregister(Editor editor) {
        GazeEngine engine = engines.remove(editor);
        if (editor.equals(lastRoutedEditor)) { lastRoutedEditor = null; lineTarget.reset(); }
        if (engine != null) engine.dispose();
    }

    public @Nullable GazeEngine engineFor(Editor editor) {
        return engines.get(editor);
    }

    void recordingBoundary() { for (GazeEngine engine : engines.values()) engine.recordingBoundary(); }

    public static void suspendAllProjects(boolean clearHistory) {
        Runnable suspend = () -> {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (project.isDisposed()) continue;
                GazeDispatcher dispatcher = getInstance(project);
                for (GazeEngine engine : dispatcher.engines.values()) engine.suspend(clearHistory);
                dispatcher.lastRoutedEditor = null;
                dispatcher.lineTarget.reset();
                if (clearHistory) GazeContextService.getInstance(project).clear();
            }
        };
        if (ApplicationManager.getApplication().isDispatchThread()) suspend.run();
        else ApplicationManager.getApplication().invokeLater(suspend);
    }

    static void trackingGapAllProjects() {
        Runnable action=()->{for(Project p:ProjectManager.getInstance().getOpenProjects())if(!p.isDisposed()) {
            var d=getInstance(p);d.engines.values().forEach(GazeEngine::trackingGap);d.lineTarget.reset();
        }};
        if(ApplicationManager.getApplication().isDispatchThread())action.run();else ApplicationManager.getApplication().invokeLater(action);
    }
    void checkpointVisits() {for(var e:engines.values())e.checkpointVisit();}

    public static void refreshCursors() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) for (GazeEngine engine : getInstance(project).engines.values()) engine.refreshCursor();
        }
    }

    // ── Screen → editor → line routing (webcam source) ────────────────────────

    /**
     * Route a screen-pixel gaze point to whichever registered editor is showing under it.
     * Must be called on the EDT (screen geometry + markup model touch Swing/editor state).
     *
     * @return {@code true} if an editor consumed the point, so the caller can stop looking
     *         in other projects.
     */
    public boolean routeScreenPoint(int screenX,int screenY){return routeScreenPoint(screenX,screenY,GazeClock.now());}
    boolean routeScreenPoint(int screenX, int screenY,long capturedAtMs) {
        Editor hit = null;
        Point local = null;

        for (Editor editor : engines.keySet()) {
            JComponent comp = editor.getContentComponent();
            if (comp == null || !comp.isShowing()) continue;
            Window owner = SwingUtilities.getWindowAncestor(comp);
            if (owner == null || !owner.isActive()) continue;
            var calibrated=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            var config=owner.getGraphicsConfiguration();
            if(config==null || !config.getDevice().equals(calibrated)) continue;
            Point origin;
            try {
                origin = comp.getLocationOnScreen();
            } catch (IllegalComponentStateException ex) {
                continue;   // not on screen right now
            }
            int lx = screenX - origin.x;
            int ly = screenY - origin.y;
            if (comp.getVisibleRect().contains(lx, ly)) {
                hit   = editor;
                local = new Point(lx, ly);
                break;
            }
        }

        if (hit == null) {
            // Gaze is no longer over any editor in this project — close out the last one.
            Editor prev = lastRoutedEditor;
            if (prev != null) {
                GazeEngine engine = engines.get(prev);
                if (engine != null) engine.onExit();
                lastRoutedEditor = null;
            }
            lineTarget.reset();
            return false;
        }

        // Gaze moved off the previously-tracked editor (e.g. another split) — close it out.
        Editor prev = lastRoutedEditor;
        if (prev != null && !prev.equals(hit)) {
            GazeEngine prevEngine = engines.get(prev);
            if (prevEngine != null) prevEngine.onExit();
        }
        Rectangle visible = hit.getContentComponent().getVisibleRect();
        Point origin = hit.getContentComponent().getLocationOnScreen();
        long stamp = hit.getDocument().getModificationStamp();
        int height = hit.getLineHeight(), wraps = hit.getSoftWrapModel().getSoftWrapsForRange(0, hit.getDocument().getTextLength()).size();
        int folds = 1;
        for (var fold : hit.getFoldingModel().getAllFoldRegions()) {
            if (fold.isValid() && !fold.isExpanded()) folds = 31 * folds + fold.getStartOffset() + fold.getEndOffset();
        }
        if (prev != hit || !visible.equals(lastVisible) || !origin.equals(lastOrigin) || stamp != lastStamp
                || height != lastLineHeight || wraps != lastWrapCount || folds != lastFoldHash) {
            lineTarget.reset();
            GazeEngine old = engines.get(hit);
            if (old != null) old.onExit();
        }
        lastVisible = visible; lastOrigin = origin; lastStamp = stamp; lastLineHeight = height; lastWrapCount = wraps; lastFoldHash = folds;
        lastRoutedEditor = hit;

        GazeEngine engine = engines.get(hit);
        if (engine == null) return false;

        LogicalPosition pos = hit.xyToLogicalPosition(local);
        if (pos.line < 0 || pos.line >= hit.getDocument().getLineCount()) { engine.onExit(); lineTarget.reset(); return true; }
        GazeInputSettings settings = GazeInputSettings.getInstance();
        if (settings.getInputMode() == GazeInputMode.WEBCAM && !settings.isMock()) {
            Point row = hit.visualPositionToXY(hit.xyToVisualPosition(local));
            int selected = lineTarget.update(pos.line, local.y, row.y + height / 2.0, height,
                    System.nanoTime() / 1_000_000, settings.getResponse().lineHoldMs);
            if (selected < 0) engine.onPendingGaze(pos.line);
            else engine.onGaze(selected, selected == pos.line ? pos.column : 0,capturedAtMs);
        } else engine.onGaze(pos.line, pos.column,capturedAtMs);
        return true;
    }
}
