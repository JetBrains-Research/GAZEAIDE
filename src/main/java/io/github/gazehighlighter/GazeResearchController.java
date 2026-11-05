package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** EDT adapter between the research panel and the existing editor/recording pipeline. */
final class GazeResearchController implements GazeResearchPanel.Controller {
    private final Project project;
    private VirtualFile demo;
    GazeResearchController(Project project) { this.project = project; }

    @Override public GazeResearchPanel.View view() {
        GazeInputSettings input = GazeInputSettings.getInstance();
        UniteyeGazeService tracker = UniteyeGazeService.getInstance();
        GazeFeatureSettings.Options options = GazeFeatureSettings.getInstance().getState();
        GazeFeatureProfile profile = GazeFeatureProfile.from(options);
        GazeResearchSettings research = GazeResearchSettings.getInstance(project);
        GazeRecordingService recording = GazeRecordingService.getInstance(project);
        GazeSession last = recording.getLastSession();
        return new GazeResearchPanel.View(currentInput(), options.paused, tracker.isRunning(), tracker.isStarting(),
                tracker.isTrackingValid(), input.isCalibrated(), GazeCalibrationDialog.isActive() || GazePersonalizationDialog.isActive(), recording.isRecording(),
                GazeRecordingService.isAnyRecording(), recording.isRecording() ? recording.eventCount() : last == null ? 0 : last.events.size(),
                recording.isRecording() ? Math.max(0, System.currentTimeMillis() - recording.getStartMs()) : last == null ? 0 : last.durationMs(),
                last != null && !last.events.isEmpty(), recording.isLastSessionExported(), research.activeScenario(profile), profile,
                !recording.isRecording() && last != null && last.metadata != null ? last.metadata.labels() : research.labels(), JetBrainsAiBridge.status(),
                GazeModeService.getInstance(project).getTarget(), tracker.getStatusMessage());
    }

    static GazeResearchPanel.Input currentInput() {
        GazeInputSettings input = GazeInputSettings.getInstance();
        return input.getInputMode() == GazeInputMode.MOUSE ? GazeResearchPanel.Input.MOUSE
                : input.isMock() ? GazeResearchPanel.Input.SIMULATION : GazeResearchPanel.Input.WEBCAM;
    }
    @Override public void setInput(GazeResearchPanel.Input choice) {
        if (configurationLocked() || choice == null) return;
        GazeDispatcher.suspendAllProjects(true);
        GazeInputSettings input = GazeInputSettings.getInstance();
        boolean changed = currentInput() != choice;
        input.setInputMode(choice == GazeResearchPanel.Input.MOUSE ? GazeInputMode.MOUSE : GazeInputMode.WEBCAM);
        input.setMock(choice == GazeResearchPanel.Input.SIMULATION);
        if (changed && choice != GazeResearchPanel.Input.MOUSE) UniteyeGazeService.getInstance().restart();
        else UniteyeGazeService.getInstance().applyMode(input.getInputMode());
    }
    @Override public void setPaused(boolean paused) {
        GazeFeatureSettings.getInstance().getState().paused = paused;
        GazeDispatcher.suspendAllProjects(true);
        GazeRecordingService.configurationChangedForOpenProjects();
    }
    @Override public void stopCamera() {
        setPaused(true);
        GazeInputSettings.getInstance().setInputMode(GazeInputMode.MOUSE);
        GazeInputSettings.getInstance().setMock(false);
        UniteyeGazeService.getInstance().applyMode(GazeInputMode.MOUSE);
        GazeRecordingService.configurationChangedForOpenProjects();
    }
    @Override public void restart() { if (!configurationLocked()) UniteyeGazeService.getInstance().restart(); }
    @Override public void calibrate() {
        if (configurationLocked()) return;
        if (currentInput() != GazeResearchPanel.Input.WEBCAM || !UniteyeGazeService.getInstance().isTrackingValid()) {
            GazeNotify.warn(project, "Wait for a valid webcam gaze sample before calibrating."); return;
        }
        GazeCalibrationDialog.open();
    }
    @Override public boolean showCursor() { return GazeInputSettings.getInstance().isShowCursor(); }
    @Override public void setShowCursor(boolean value) {
        GazeInputSettings.getInstance().setShowCursor(value);
        GazeDispatcher.refreshCursors();
        GazeRecordingService.presentationChangedForOpenProjects();
    }
    @Override public GazeResponse response() { return GazeInputSettings.getInstance().getStandardResponse(); }
    @Override public void setResponse(GazeResponse value) {
        if (value == null || value == response() || configurationLocked() || GazeCalibrationDialog.isActive() || GazePersonalizationDialog.isActive()) return;
        GazeInputSettings.getInstance().setResponse(value);
        UniteyeGazeService.getInstance().resetFilter();
        GazeRecordingService.configurationChangedForOpenProjects();
    }
    @Override public void checkAccuracy() {
        if (!configurationLocked() && currentInput() == GazeResearchPanel.Input.WEBCAM) GazeCalibrationDialog.checkAccuracy();
    }
    @Override public String calibrationSummary() { return GazeInputSettings.getInstance().calibrationSummary(); }
    @Override public boolean hasPersonalProfile() { return GazeInputSettings.getInstance().hasPersonalResponse(); }
    @Override public boolean usesPersonalProfile() { return GazeInputSettings.getInstance().isPersonalResponseActive(); }
    @Override public String personalProfileSummary() { return GazeInputSettings.getInstance().personalResponseSummary(); }
    @Override public void usePersonalProfile(boolean use) {
        if (configurationLocked() || GazePersonalizationDialog.isActive()) return;
        GazeInputSettings.getInstance().setUsePersonalResponse(use); UniteyeGazeService.getInstance().resetFilter();
    }
    @Override public void resetPersonalProfile() {
        if (configurationLocked() || !hasPersonalProfile()) return;
        if (Messages.showYesNoDialog(project, "Delete the saved IDE-user response profile and use standard settings?", "Reset personal profile", Messages.getQuestionIcon()) != Messages.YES) return;
        GazeInputSettings.getInstance().clearPersonalResponse(); UniteyeGazeService.getInstance().resetFilter();
    }
    @Override public boolean personalize(GazeSessionMetadata.Labels labels) {
        if (configurationLocked()) return false;
        GazeResearchSettings.getInstance(project).setLabels(labels); setPaused(true);
        return GazePersonalizationDialog.open(project);
    }
    @Override public GazeReviewPurpose reviewPurpose() { return GazeResearchSettings.getInstance(project).reviewPurpose(); }
    @Override public boolean quietOffers() { return GazeResearchSettings.getInstance(project).getState().quietOffers; }
    @Override public void setReviewOptions(GazeReviewPurpose purpose, boolean quiet) {
        if (configurationLocked()) return;
        var options = GazeResearchSettings.getInstance(project).getState(); options.reviewPurpose = purpose.name(); options.quietOffers = quiet;
    }
    private boolean offersEnabled() {
        var recording = GazeRecordingService.getInstance(project);
        var options = GazeFeatureSettings.getInstance().getState();
        return recording.isRecording() && !options.paused && quietOffers()
                && GazeResearchSettings.getInstance(project).activeScenario(GazeFeatureProfile.from(options)) == GazeScenario.RELATIONSHIPS;
    }
    @Override public String offerStatus() {
        var offer = GazeContextService.getInstance(project).offer(offersEnabled());
        return offer == null ? "" : offer.purpose() + "\nCaptured: " + offer.region().label() + " [" + offer.region().source() + "]";
    }
    @Override public void openOffer() {
        var service = GazeContextService.getInstance(project); var offer = service.offer(offersEnabled());
        if (offer == null) return;
        service.dismissOffer(); JetBrainsAiBridge.showRequest(project, GazePrompt.SYSTEM + "\n\n" + offer.prompt(), offer.region());
    }
    @Override public void dismissOffer() { GazeContextService.getInstance(project).dismissOffer(); }
    @Override public void applyScenario(GazeScenario scenario) {
        if (configurationLocked() || scenario == GazeScenario.CUSTOM) return;
        GazeDispatcher.suspendAllProjects(true);
        scenario.profile().applyTo(GazeFeatureSettings.getInstance().getState());
        GazeResearchSettings.getInstance(project).getState().scenario = scenario.name();
    }
    @Override public void applyFeatures(GazeFeatureProfile profile) {
        if (configurationLocked()) return;
        GazeDispatcher.suspendAllProjects(true);
        profile.applyTo(GazeFeatureSettings.getInstance().getState());
        GazeResearchSettings.getInstance(project).getState().scenario = GazeScenario.CUSTOM.name();
    }
    @Override public boolean startSession(GazeSessionMetadata.Labels labels) {
        GazeRecordingService rec = GazeRecordingService.getInstance(project);
        if (rec.isRecording()) return false;
        if (GazeCalibrationDialog.isActive() || GazePersonalizationDialog.isActive() || UniteyeGazeService.getInstance().isStarting()) {
            GazeNotify.warn(project, "Finish connecting and calibrating the input before starting a session."); return false;
        }
        if (currentInput() == GazeResearchPanel.Input.WEBCAM && !UniteyeGazeService.getInstance().isTrackingValid()) {
            GazeNotify.warn(project, "Wait for a valid webcam gaze sample before starting a session."); return false;
        }
        GazeResearchSettings.getInstance(project).setLabels(labels);
        setPaused(false);
        rec.start();
        GazeReviewTourService.getInstance(project).beginSession(rec.getStartMs(), labels.task(),
                GazeResearchSettings.getInstance(project).activeScenario(GazeFeatureProfile.from(GazeFeatureSettings.getInstance().getState())) == GazeScenario.WALKTHROUGH);
        return true;
    }
    @Override public void stopSession() { GazeRecordingService.getInstance(project).stop(); setPaused(true); }
    @Override public List<GazeResearchPanel.SavedSession> savedSessions() {
        var format = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
        return GazeSessionLibrary.getInstance(project).sessions().stream().map(s -> new GazeResearchPanel.SavedSession(
                GazeSessionStore.id(s), format.format(Instant.ofEpochMilli(s.startMs)) + " · "
                + (s.metadata == null || s.metadata.labels().participant().isBlank() ? "unlabelled" : s.metadata.labels().participant())
                + (s.metadata == null || s.metadata.labels().task().isBlank() ? "" : " · " + s.metadata.labels().task()))).toList();
    }
    @Override public boolean openSession(String id) {
        var session = GazeSessionLibrary.getInstance(project).sessions().stream().filter(s -> GazeSessionStore.id(s).equals(id)).findFirst();
        return session.isPresent() && GazeRecordingService.getInstance(project).selectSession(session.get());
    }
    @Override public String storageStatus() { return GazeSessionLibrary.getInstance(project).status(); }
    @Override public String remoteStatus() { return GazeRemoteUploadService.getInstance(project).status(); }
    @Override public String contextStatus() {
        return GazeContextService.getInstance(project).status(GazeFeatureSettings.getInstance().getState().explanations);
    }
    @Override public void perform(GazeResearchPanel.Action action) {
        switch (action) {
            case GUIDE -> new GazeHelpDialog(project).show();
            case REMOTE -> { if (!GazeRecordingService.getInstance(project).isRecording()) new GazeRemoteUploadDialog(project).show(); }
            case AI_SETTINGS -> JetBrainsAiBridge.openChat(project);
            case ASK_AI -> JetBrainsAiBridge.askViewed(project);
            case DEMO -> openDemo();
            case WALKTHROUGH -> GazeReviewTourService.getInstance(project).open(GazeRecordingService.getInstance(project).getLastSession());
            default -> {
                String id = switch (action) {
                    case REPORT -> "ReadReport";
                    case EXPORT -> "ExportSession";
                    case TRACE -> "BuildWalkthrough";
                    case CONTEXT -> "ShowContext";
                    default -> throw new IllegalStateException();
                };
                AnAction registered = ActionManager.getInstance().getAction("io.github.gazehighlighter." + id);
                if (registered != null) registered.actionPerformed(AnActionEvent.createFromAnAction(registered,
                        null, ActionPlaces.TOOLWINDOW_CONTENT, SimpleDataContext.getProjectContext(project)));
            }
        }
    }
    private boolean configurationLocked() {
        if (!GazeRecordingService.isAnyRecording()) return false;
        GazeNotify.warn(project, "Stop the active recording before changing its input, scenario or feature settings."); return true;
    }
    private void openDemo() {
        try {
            if (demo == null || !demo.isValid()) {
                var directory = Files.createTempDirectory("implicit-gaze-demo-");
                directory.toFile().deleteOnExit();
                var file = directory.resolve("GazeReviewDemo.java");
                try (InputStream source = getClass().getResourceAsStream("/demo/GazeReviewDemo.java")) {
                    if (source == null) throw new java.io.IOException("Demo resource missing");
                    Files.copy(source, file);
                }
                file.toFile().deleteOnExit();
                demo = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file.toFile());
            }
            if (demo != null) FileEditorManager.getInstance(project).openFile(demo, true);
        } catch (Exception ex) { GazeNotify.warn(project, "Could not open the demo code. Check the plugin log/resources."); }
    }
    static GazeSessionMetadata captureMetadata(Project project) {
        GazeResearchSettings research = GazeResearchSettings.getInstance(project);
        GazeFeatureProfile profile = GazeFeatureProfile.from(GazeFeatureSettings.getInstance().getState());
        return new GazeSessionMetadata(research.labels(), new GazeSessionMetadata.InitialConfiguration(
                switch (currentInput()) { case MOUSE -> "MOUSE_PROXY"; case WEBCAM -> "WEBCAM"; case SIMULATION -> "MOUSE_SIMULATION"; },
                research.activeScenario(profile).name(), profile, currentInput() == GazeResearchPanel.Input.WEBCAM && GazeInputSettings.getInstance().isCalibrated(),
                "JetBrains AI Chat", "Chosen in native chat; not observed by GAZEAIDE", captureTracking(),
                new GazeSessionMetadata.ReviewConfiguration(research.reviewPurpose().name(), research.getState().quietOffers, GazeAssistanceOffer.COOLDOWN_MS)));
    }
    private static GazeSessionMetadata.TrackingConfiguration captureTracking() {
        var input = GazeInputSettings.getInstance(); var state = input.getState();
        boolean webcam = currentInput() == GazeResearchPanel.Input.WEBCAM;
        GazeResponse response = input.getResponse();
        return new GazeSessionMetadata.TrackingConfiguration(input.isShowCursor(), webcam ? response.name() : "BYPASSED",
                webcam ? response.smoothingMs : 0, webcam ? response.lineHoldMs : 0,
                webcam ? "raw-eyemu-median3-time-lowpass-line-hold-v2" : "mouse-direct-v1", webcam && input.isCalibrated()
                ? new GazeSessionMetadata.CalibrationCheck(state.calibrationVersion, state.checkedAtMs,
                    state.displayX, state.displayY, state.displayWidth, state.displayHeight,
                    state.calA, state.calB, state.calC, state.calD, state.calE, state.calF,
                    state.medianErrorPx, state.p90ErrorPx, state.medianVerticalPx, state.p90VerticalPx,
                    state.jitter90Px, state.validationSamples, state.validationTargets) : null, personalSnapshot(webcam));
    }
    private static GazeSessionMetadata.PersonalResponse personalSnapshot(boolean webcam) {
        var input = GazeInputSettings.getInstance(); if (!input.hasPersonalResponse()) return null;
        var p = input.personalResponse();
        return new GazeSessionMetadata.PersonalResponse(p.version, p.createdAtMs, p.response, webcam && input.isPersonalResponseActive(),
                p.p90VerticalPx, p.jitter90Px, p.targetHitRate, p.acquisitionMs, p.standardHitRate, p.standardAcquisitionMs,
                p.rowHeight, p.validationTargets, p.validationSamples);
    }
}
