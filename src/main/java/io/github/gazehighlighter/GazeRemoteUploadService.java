package io.github.gazehighlighter;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Opt-in project uploader. Nothing is sent at startup; background work never blocks recording. */
@Service(Service.Level.PROJECT)
public final class GazeRemoteUploadService implements Disposable {
    private final Project project;
    private final GazeRemoteOutbox outbox;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile String status = "No upload attempted in this IDE session.";
    private volatile int pending;
    private volatile java.util.concurrent.Future<?> active;
    public GazeRemoteUploadService(Project project) {
        this.project = project;
        String identity = project.getBasePath() == null ? project.getLocationHash() : project.getBasePath();
        outbox = new GazeRemoteOutbox(Path.of(PathManager.getSystemPath(), "implicit-gaze", "remote-outbox", GazeSessionStore.projectKey(identity)));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { recount(); }
            catch (IOException ex) { status = "Upload queue could not be read. Local recordings are unaffected."; }
        });
    }
    static GazeRemoteUploadService getInstance(Project project) { return project.getService(GazeRemoteUploadService.class); }
    GazeRemoteConnection connection() { return GazeRemoteSettings.getInstance(project).connection(); }
    boolean isBusy() { return busy.get(); }
    String preview(GazeSession session, GazeRemoteConnection c) throws IOException {
        var existing = outbox.find(session, c);
        return GazeRemotePayload.encoded(existing == null ? GazeRemotePayload.session(session, c, java.util.UUID.randomUUID().toString()) : existing.payload());
    }
    String status() {
        var c = connection();
        return (c.enabled() ? "Remote upload enabled · " + (c.automatic() ? "after future recordings" : "manual only")
                : "Remote upload off · local recording only") + "\n" + status + (pending > 0 ? "\n" + pending + " pending upload(s). Retry sends only to their original connection." : "");
    }
    private CredentialAttributes credentials(GazeRemoteConnection c) {
        return new CredentialAttributes("GAZEAIDE remote upload / " + project.getLocationHash() + " / " + c.binding());
    }
    void save(GazeRemoteConnection c, String replacementToken, Consumer<String> done) {
        String problem = c.problem();
        if (c.enabled() && problem != null) { done.accept(problem); return; }
        if (!replacementToken.isBlank() && !GazeRemoteConnection.validToken(replacementToken)) {
            done.accept("Use a participant upload token of 32–256 letters, digits, underscores or hyphens; do not enter a cloud admin key."); return;
        }
        worker(() -> {
            if (GazeRecordingService.getInstance(project).isRecording()) throw new GazeRemoteException("Stop recording before changing the upload connection.");
            if (!replacementToken.isBlank()) PasswordSafe.getInstance().setPassword(credentials(c), replacementToken);
            if (c.enabled() && !GazeRemoteConnection.validToken(PasswordSafe.getInstance().getPassword(credentials(c))))
                throw new GazeRemoteException("Enter the participant token supplied by your study receiver. No connection has been enabled.");
            GazeRemoteSettings.getInstance(project).save(c);
            status = c.enabled() ? "Connection saved. Use Test connection with synthetic data; separately validate the study setup before recruitment."
                    : "Remote upload disabled. No pending uploads will be sent.";
        }, done);
    }
    void disconnect(Consumer<String> done) {
        worker(() -> {
            if (GazeRecordingService.getInstance(project).isRecording()) throw new GazeRemoteException("Stop recording before disconnecting.");
            var c = connection();
            // Disable even if removing a credential subsequently fails.
            GazeRemoteSettings.getInstance(project).save(new GazeRemoteConnection(c.connector(), c.endpoint(), c.studyId(), c.participantId(), c.taskCode(), false, false, c.acknowledged()));
            PasswordSafe.getInstance().setPassword(credentials(c), null);
            status = "Disconnected and removed this connection's stored token. Pending uploads stay local; deleting cloud data is a separate action.";
        }, done);
    }
    void test(Consumer<String> done) {
        worker(() -> {
            var c = requireConnection();
            send(c, GazeRemotePayload.test(c));
            status = "Synthetic test acknowledged. No recording was sent. This does not validate ethics, data suitability, retention or end-to-end study operation.";
        }, done);
    }
    void uploadSelected(Consumer<String> done) {
        if (GazeRecordingService.getInstance(project).isRecording()) { done.accept("Stop recording before uploading a session."); return; }
        GazeSession session = GazeRecordingService.getInstance(project).getLastSession();
        if (session == null) { done.accept("Record a session or reopen a saved recording first."); return; }
        var c = connection();
        worker(() -> { requireSame(c); sendEntry(c, outbox.enqueue(session, c)); }, done);
    }
    /** Captured at Start: enabling upload later can never send an older session automatically. */
    GazeRemoteConnection automaticAtStart() {
        var c = connection(); return c.canUpload() && c.automatic() ? c : null;
    }
    void recordingStopped(GazeSession session, GazeRemoteConnection atStart) {
        if (atStart == null || !atStart.equals(connection()) || !atStart.canUpload()) return;
        ApplicationManager.getApplication().executeOnPooledThread(()->{
            try {
                var entry=outbox.enqueue(session,atStart);recount();
                if(busy.get()){status="Automatic upload queued. Retry pending uploads when the current operation finishes.";return;}
                worker(()->{requireSame(atStart);sendEntry(atStart,entry);},message->{});
            }catch(IOException e){status="Could not queue automatic upload. The full recording remains in the local library.";}
        });
    }
    void retry(Consumer<String> done) {
        worker(() -> {
            var c = requireConnection(); int sent = 0;
            for (var entry : outbox.entries()) {
                if (!entry.sent() && entry.binding().equals(c.binding())) { sendEntry(c, entry); sent++; }
            }
            status = sent == 0 ? "No pending uploads match this connection. Other studies remain local; restore their original connection to retry."
                    : "Acknowledged " + sent + " pending upload(s).";
        }, done);
    }
    void clearPending(Consumer<String> done) {
        worker(() -> { outbox.clearPending(); status = "Deleted pending reduced payloads on this computer. Full local recordings and already uploaded cloud data are unchanged."; }, done);
    }
    private GazeRemoteConnection requireConnection() throws IOException {
        var c = connection(); if (!c.canUpload()) throw new GazeRemoteException("Save and enable a valid connection first. Upload is off by default."); return c;
    }
    private void requireSame(GazeRemoteConnection c) throws IOException {
        if (!requireConnection().equals(c)) throw new GazeRemoteException("Connection changed. Review it before uploading.");
    }
    private void send(GazeRemoteConnection c, com.google.gson.JsonObject payload) throws IOException, InterruptedException {
        requireSame(c);
        new GazeRemoteTransport().upload(c, PasswordSafe.getInstance().getPassword(credentials(c)), payload);
    }
    private void sendEntry(GazeRemoteConnection c, GazeRemoteOutbox.Entry entry) throws IOException, InterruptedException {
        if (entry.sent()) { status = "This recording was already acknowledged for this connection. No duplicate was sent."; return; }
        if (!entry.binding().equals(c.binding())) throw new GazeRemoteException("Queued upload belongs to a different connection and was not sent.");
        status = "Uploading reduced dataset…"; send(c, entry.payload()); outbox.markSent(entry);
        status = "Reduced dataset acknowledged by the receiver. Full source snapshots remain local.";
    }
    private void recount() throws IOException { pending = (int) outbox.entries().stream().filter(e -> !e.sent()).count(); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void worker(Work work, Consumer<String> done) {
        if (!busy.compareAndSet(false, true)) { done.accept("An upload operation is already running. Wait for its status."); return; }
        active = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { if (!project.isDisposed()) work.run(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); status = "Upload interrupted. Pending data remains local; retry explicitly."; }
            catch (IOException | IllegalArgumentException ex) {
                // Only our own validation messages are safe; never display server bodies, URLs or tokens.
                status = safeFailure(ex);
            }
            catch (Exception ex) { status = "Upload operation failed. Check the network, receiver and IDE password storage. Local recordings are unaffected."; }
            finally {
                try { recount(); } catch (IOException ex) { status = "Upload queue could not be updated. Local recordings are unaffected."; }
                busy.set(false);
                ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed()) done.accept(status()); });
            }
        });
    }
    @Override public void dispose() { var task = active; if (task != null) task.cancel(true); }
    private static String safeFailure(Exception ex) {
        // JDK network exception messages can contain endpoint details. Expose only plugin-authored messages.
        if (ex instanceof GazeRemoteException || ex.getClass() == IllegalArgumentException.class) {
            String message = ex.getMessage();
            if (message != null && !message.contains("://") && message.length() < 400) return message;
        }
        return "Upload failed. Check the connection, network and receiver; pending data remains local for explicit retry.";
    }
}
