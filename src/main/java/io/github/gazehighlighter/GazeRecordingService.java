package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Project-scoped recording of {@link GazeEvent}s — the "record a session, then export it" half
 * of Implicit, independent of (and running alongside) the reading/dwell/AI-explanation pipeline.
 *
 * <p>{@link GazeEngine} calls {@link #record} once per line visit whenever a session is active;
 * this service just buffers those events until {@link #stop()} freezes them into a
 * {@link GazeSession} for {@link GazeSessionExporter}.
 */
@Service(Service.Level.PROJECT)
public final class GazeRecordingService {

    private final Project project;

    private volatile boolean recording = false;
    private volatile long    startMs   = 0;
    private final List<GazeEvent> events = new ArrayList<>();
    private final List<GazeSession.ConfigurationChange> configurationChanges = new ArrayList<>();
    private GazeSessionMetadata metadata;
    private GazeBlinkMonitor blinkMonitor;
    private GazeRemoteConnection remoteAtStart;
    private volatile boolean lastSessionExported;

    /** The most recently stopped session, kept around so Export can be a separate, later step. */
    private volatile @Nullable GazeSession lastSession;

    /** Set by the status bar widget so it can repaint on start/stop; cleared on dispose. */
    private @Nullable Runnable onChange;

    public GazeRecordingService(Project project) {
        this.project = project;
    }

    public static GazeRecordingService getInstance(Project project) {
        return project.getService(GazeRecordingService.class);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    public synchronized void start() {
        if (recording) return;
        GazeDispatcher.getInstance(project).recordingBoundary();
        events.clear();
        configurationChanges.clear();
        metadata = GazeResearchController.captureMetadata(project);
        remoteAtStart = GazeRemoteUploadService.getInstance(project).automaticAtStart();
        startMs   = System.currentTimeMillis();
        var research=GazeResearchSettings.getInstance(project);
        var blinkConfig=research.blinkConfig();
        blinkMonitor=research.getState().collectBlinks && GazeResearchController.currentInput()==GazeResearchPanel.Input.WEBCAM
                ? new GazeBlinkMonitor(startMs,blinkConfig,GazeBlinkMonitor.reference(GazeSessionLibrary.getInstance(project).sessions(),blinkConfig,startMs)) : null;
        GazeContextService.getInstance(project).beginSession();
        recording = true;
        configurationChanged();
        fireChange();
    }

    /** Stops recording and returns an immutable snapshot of what was captured. */
    public synchronized GazeSession stop() {
        if (!recording && lastSession != null) return lastSession;
        if (recording) GazeDispatcher.getInstance(project).recordingBoundary();
        recording = false;
        if(blinkMonitor!=null)blinkMonitor.interrupt(System.currentTimeMillis(),"recording_stopped");
        GazeSession session = new GazeSession(project.getName(), startMs,
                System.currentTimeMillis(), new ArrayList<>(events), metadata, configurationChanges, blinkMonitor==null?null:blinkMonitor.snapshot(System.currentTimeMillis()));
        lastSession = session;
        lastSessionExported = false;
        GazeSessionLibrary.getInstance(project).save(session);
        GazeRemoteUploadService.getInstance(project).recordingStopped(session, remoteAtStart);
        remoteAtStart = null;
        fireChange();
        return session;
    }

    public boolean isRecording() {
        return recording;
    }

    public static boolean isAnyRecording() {
        for (Project open : ProjectManager.getInstance().getOpenProjects())
            if (!open.isDisposed() && getInstance(open).isRecording()) return true;
        return false;
    }

    public static void configurationChangedForOpenProjects() {
        for (Project open : ProjectManager.getInstance().getOpenProjects()) {
            if (open.isDisposed()) continue;
            GazeRecordingService rec = getInstance(open);
            if (rec.isRecording()) {
                GazeDispatcher.getInstance(open).recordingBoundary();
                rec.configurationChanged();
            }
        }
    }

    /** Log a cursor-visibility change without splitting visits or resetting context dwell. */
    static void presentationChangedForOpenProjects() {
        for (Project open : ProjectManager.getInstance().getOpenProjects())
            if (!open.isDisposed()) getInstance(open).configurationChanged();
    }

    private synchronized void configurationChanged() {
        if (!recording) return;
        var config = GazeResearchController.captureMetadata(project).initialConfiguration();
        boolean paused = GazeFeatureSettings.getInstance().getState().paused;
        if(paused && blinkMonitor!=null)blinkMonitor.interrupt(System.currentTimeMillis(),"paused");
        var previous = configurationChanges.isEmpty() ? null : configurationChanges.get(configurationChanges.size() - 1);
        if (previous == null || previous.paused() != paused || !previous.configuration().equals(config))
            configurationChanges.add(new GazeSession.ConfigurationChange(Math.max(0, System.currentTimeMillis() - startMs), paused, config));
    }

    public boolean isLastSessionExported() { return lastSessionExported; }
    public synchronized void markExported(GazeSession session) {
        if (lastSession == session) lastSessionExported = true;
    }

    /** The most recently stopped session (survives after {@link #stop()} returns), or
     *  {@code null} if nothing has been recorded yet this IDE session. */
    public @Nullable GazeSession getLastSession() {
        return lastSession;
    }

    public synchronized boolean selectSession(GazeSession session) {
        if (recording) return false;
        lastSession = session;
        lastSessionExported = false;
        fireChange();
        return true;
    }

    /** Whether there is a session worth exporting/reporting on — recording now, or already stopped. */
    public boolean hasData() {
        return recording || lastSession != null;
    }

    /** Stops an in-progress recording and returns it, or the last stopped session otherwise.
     *  Shared by Export/Read-report, which both treat "act on this" the same way. */
    public synchronized @Nullable GazeSession finishedSession() {
        if (!recording) return lastSession;
        GazeSession session = stop();
        new GazeResearchController(project).setPaused(true);
        return session;
    }

    long getStartMs() {
        return startMs;
    }

    /** A live snapshot without stopping the recording (e.g. to show "N events so far"). */
    public synchronized int eventCount() {
        return events.size();
    }

    /** Current completed visits, or the selected stopped recording; never stops recording. */
    synchronized @Nullable GazeSession contextSnapshot() {
        return recording ? new GazeSession(project.getName(), startMs, System.currentTimeMillis(),
                events, metadata, configurationChanges, blinkMonitor==null?null:blinkMonitor.snapshot(System.currentTimeMillis())) : lastSession;
    }

    // ── Recording ────────────────────────────────────────────────────────────────

    synchronized void record(GazeEvent event) {
        if (recording) events.add(event);
    }

    static void eyeSampleForOpenProjects(long at,double left,double right,boolean face) {
        for(Project p:ProjectManager.getInstance().getOpenProjects()) if(!p.isDisposed()) {
            var rec=getInstance(p);if(!rec.recording||rec.blinkMonitor==null)continue;
            var frame=com.intellij.openapi.wm.WindowManager.getInstance().getFrame(p);
            synchronized(rec) {
                if(GazeFeatureSettings.getInstance().getState().paused||frame==null||!frame.isActive())rec.blinkMonitor.interrupt(at,"paused_or_unfocused");
                else rec.blinkMonitor.sample(at,left,right,face);
            }
        }
    }
    String blinkStatus() {var s=contextSnapshot();return GazeBlinkMonitor.status(s==null?null:s.blink);}

    // ── Widget hookup ────────────────────────────────────────────────────────────

    void setOnChangeListener(@Nullable Runnable listener) {
        this.onChange = listener;
    }

    private void fireChange() {
        Runnable r = onChange;
        if (r != null) ApplicationManager.getApplication().invokeLater(r);
    }
}
