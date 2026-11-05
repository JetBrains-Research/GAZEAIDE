package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.ContentFactory;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Project-local tour, captured from explicit open code or a selected recording. No automatic AI calls. */
@Service(Service.Level.PROJECT)
public final class GazeReviewTourService implements GazeReviewTourPanel.Controller {
    private final Project project;
    private final GazeTourStore store;
    private final javax.swing.Timer saveTimer;
    private GazeReviewTour tour = new GazeReviewTour("", "No code loaded", List.of());
    private String status = "Load open code or open a saved session's walkthrough.";
    private long generation, sessionStart;
    private long editRevision, saveRevision;
    private GazeReviewTourPanel panel;
    public GazeReviewTourService(Project project) {
        this.project = project;
        store = new GazeTourStore(GazeSessionLibrary.getInstance(project).walkthroughDirectory());
        saveTimer = new javax.swing.Timer(800, e -> saveEdits()); saveTimer.setRepeats(false);
    }
    public static GazeReviewTourService getInstance(Project p) { return p.getService(GazeReviewTourService.class); }
    public void beginSession(long start, String goal, boolean capture) {
        saveEdits();
        generation++;
        tour = new GazeReviewTour(goal, "No code loaded", List.of());
        if (capture) captureOpenFiles(goal);
        sessionStart = start;
    }
    public void captureOpenFiles(String goal) {
        List<GazeReviewTour.Source> sources = new ArrayList<>();
        for (var file : FileEditorManager.getInstance(project).getOpenFiles()) {
            if (!file.isInLocalFileSystem()) continue;
            var doc = FileDocumentManager.getInstance().getCachedDocument(file); if (doc == null) continue;
            sources.add(new GazeReviewTour.Source(file.getPath(), doc.getText(new TextRange(0, Math.min(64001, doc.getTextLength()))), doc.getModificationStamp()));
            if (sources.size() == 9) break;
        }
        long request = ++generation; sessionStart = 0; status = "Capturing open code snapshots…";
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            GazeReviewTour captured = GazeReviewTour.fromSources(goal, sources);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || generation != request) return;
                tour = captured; status = captured.steps.isEmpty() ? "No text code captured. Open a source file, then reload." : "Code captured. Look at a block to select it; ask JetBrains AI when ready.";
                saveEdits();
            });
        });
    }
    public void open(GazeSession session) {
        if (session != null && !GazeRecordingService.getInstance(project).isRecording() && (sessionStart != session.startMs || tour.steps.isEmpty())) {
            saveEdits(); generation++; tour = GazeReviewTour.fromSession(session); sessionStart = session.startMs;
            status = "Recorded snapshots loaded. Gaze matching requires the same document version; manual exploration is always available.";
            long revision = generation, edits = editRevision;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    GazeReviewTour saved = store.load(session.startMs);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed() && generation == revision && editRevision == edits && saved != null) tour = saved;
                    });
                } catch (Exception ignored) { /* Recorded source remains available if a draft cannot be read. */ }
            });
        } else if (tour.steps.isEmpty()) {
            long bound = sessionStart;
            captureOpenFiles(GazeResearchSettings.getInstance(project).labels().task()); sessionStart = bound;
        }
        var window = ToolWindowManager.getInstance(project).getToolWindow(GazeResearchToolWindowFactory.ID);
        if (window == null) return;
        if (panel == null) {
            panel = new GazeReviewTourPanel(this); var owned = panel;
            var content = ContentFactory.getInstance().createContent(panel, "Review walkthrough", true);
            content.setDisposer(() -> { owned.dispose(); if (panel == owned) panel = null; });
            window.getContentManager().addContent(content); owned.startRefreshing();
        }
        var content = window.getContentManager().getContent(panel);
        if (content != null) window.getContentManager().setSelectedContent(content);
        window.activate(null);
    }
    @Override public GazeReviewTour tour() { return tour; }
    @Override public ViewedRegion viewed() { return GazeModeService.getInstance(project).getViewedRegion(); }
    @Override public String status() { return status; }
    @Override public String aiStatus() { return JetBrainsAiBridge.status(); }
    @Override public void openAiChat() { JetBrainsAiBridge.openChat(project); }
    @Override public void backToExperiment() { OpenGazeResearchAction.open(project); }
    @Override public boolean selectionPaused() { return GazeFeatureSettings.getInstance().getState().paused; }
    @Override public void resumeSelection() { new GazeResearchController(project).setPaused(false); }
    @Override public void loadOpenFiles() {
        if (!tour.steps.isEmpty() && JOptionPane.showConfirmDialog(panel, "Replace this tour and its notes with current open code? Export first to keep this tour.",
                "Reload walkthrough", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        long bound = sessionStart; saveEdits();
        captureOpenFiles(GazeResearchSettings.getInstance(project).labels().task()); sessionStart = bound;
    }
    @Override public void explain() {
        if (tour.steps.isEmpty()) return;
        var selected = panel == null ? null : panel.selectedStep();
        JetBrainsAiBridge.showRequest(project, tour.nativePrompt(selected == null ? null : selected.id),
                selected == null ? null : selected.region);
        status = "Explanations open in JetBrains AI Chat. Paste and send the prepared prompt there; add useful findings to Your notes.";
    }
    @Override public void changed() { editRevision++; saveTimer.restart(); }
    @Override public void saveEdits() {
        saveTimer.stop();
        if (sessionStart <= 0 || tour.steps.isEmpty()) return;
        long start = sessionStart, revision = ++saveRevision; String snapshot = GazeTourStore.snapshot(tour);
        // A quick session switch must see this edit even if its disk save has not run yet.
        store.remember(start, revision, snapshot);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { store.save(start, revision, snapshot); }
            catch (Exception e) { if (!project.isDisposed()) GazeNotify.warn(project, "Could not save walkthrough edits locally. Export the walkthrough to keep them."); }
        });
    }
    @Override public void navigate(GazeReviewTour.Step step) {
        var file = LocalFileSystem.getInstance().findFileByPath(step.region.filePath());
        var doc = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
        if (doc == null || doc.getModificationStamp() != step.region.documentStamp() || !GazeReviewTour.matchesSource(doc.getText(), step.region)) {
            status = "Current source does not match this snapshot, or is unavailable. Use the captured snapshot or reload current code."; return;
        }
        new OpenFileDescriptor(project, file, step.region.firstLine() - 1, 0).navigate(true);
    }
    @Override public void export() {
        Path path = GazeHtmlSaveDialog.choose(project, panel, "Export review walkthrough", "code-review-walkthrough.html");
        if (path == null) return;
        String html = tour.html();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { Files.writeString(path, html); GazeNotify.info(project, "Review walkthrough exported to " + path); }
            catch (Exception e) { GazeNotify.warn(project, "Could not save the walkthrough. Choose a writable location and try again."); }
        });
    }
}
