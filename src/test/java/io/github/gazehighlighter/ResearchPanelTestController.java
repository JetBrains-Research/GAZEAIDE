package io.github.gazehighlighter;

import java.util.ArrayList;
import java.util.List;

/** Deterministic controller for native component tests/preview; no IDE, camera or provider. */
class ResearchPanelTestController implements GazeResearchPanel.Controller {
    GazeResearchPanel.Input input = GazeResearchPanel.Input.MOUSE;
    boolean paused = true, running, starting, tracking, calibrated, recording, hasSession, exported, calibrating;
    String trackerMessage = "Valid gaze samples received.";
    int eventCount, stopCameraCalls;
    long duration;
    boolean allowStart = true;
    String opened;
    String contextStatus = "Automatic AI context is off in this scenario.";
    boolean cursor;
    int accuracyChecks;
    GazeResponse response = GazeResponse.STEADY;
    boolean personalProfile, personalActive, allowPersonalization = true, quiet = true;
    int personalizations, openedOffers, dismissedOffers;
    GazeReviewPurpose purpose = GazeReviewPurpose.COMPARE;
    String offer = "";
    final List<GazeResearchPanel.SavedSession> saved = new ArrayList<>();
    GazeFeatureProfile profile = GazeScenario.FOCUS.profile();
    GazeScenario scenario = GazeScenario.FOCUS;
    GazeSessionMetadata.Labels labels = new GazeSessionMetadata.Labels("", "", "");
    final List<GazeResearchPanel.Action> actions = new ArrayList<>();
    public GazeResearchPanel.View view() {
        return new GazeResearchPanel.View(input, paused, running, starting, tracking, calibrated, calibrating,
                recording, recording, eventCount, duration, hasSession, exported, scenario, profile, labels,
                "JetBrains AI Chat detected. Sign in and choose the assistant/model in native chat.", "No active code target", trackerMessage);
    }
    public void setInput(GazeResearchPanel.Input input) { this.input = input; running = input != GazeResearchPanel.Input.MOUSE; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public void stopCamera() { stopCameraCalls++; input = GazeResearchPanel.Input.MOUSE; running = false; paused = true; }
    public void restart() { running = true; }
    public void calibrate() { calibrating = true; }
    public void applyScenario(GazeScenario scenario) { this.scenario = scenario; profile = scenario.profile(); }
    public void applyFeatures(GazeFeatureProfile profile) { this.profile = profile; scenario = GazeScenario.CUSTOM; }
    public boolean startSession(GazeSessionMetadata.Labels labels) { if (!allowStart) return false; this.labels = labels; paused = false; recording = true; return true; }
    public void stopSession() { recording = false; paused = true; hasSession = true; eventCount = 12; duration = 43000; }
    public List<GazeResearchPanel.SavedSession> savedSessions() { return List.copyOf(saved); }
    public boolean openSession(String id) { opened = id; hasSession = true; return true; }
    public void perform(GazeResearchPanel.Action action) { actions.add(action); if (action == GazeResearchPanel.Action.EXPORT) exported = true; }
    public String contextStatus() { return contextStatus; }
    public boolean showCursor() { return cursor; }
    public void setShowCursor(boolean value) { cursor = value; }
    public GazeResponse response() { return response; }
    public void setResponse(GazeResponse value) { if (!recording) response = value; }
    public void checkAccuracy() { accuracyChecks++; }
    public String calibrationSummary() { return calibrated ? "Accuracy check: vertical error median 14 px; 90th percentile 35 px." : "Not calibrated. Calibrate on the primary display before a study."; }
    public boolean hasPersonalProfile() { return personalProfile; }
    public boolean usesPersonalProfile() { return personalActive; }
    public void usePersonalProfile(boolean use) { personalActive = use && personalProfile; }
    public void resetPersonalProfile() { personalProfile = personalActive = false; }
    public String personalProfileSummary() { return personalProfile ? "Personal profile: Balanced. Fresh-target check: 90% target-row samples; p90 error 5 px." : "No personal profile yet. Choose Personalization to create one for all scenarios."; }
    public boolean personalize(GazeSessionMetadata.Labels labels) { personalizations++; if (!allowPersonalization) return false; personalProfile = personalActive = true; return true; }
    public GazeReviewPurpose reviewPurpose() { return purpose; }
    public boolean quietOffers() { return quiet; }
    public void setReviewOptions(GazeReviewPurpose purpose, boolean quiet) { this.purpose = purpose; this.quiet = quiet; }
    public String offerStatus() { return offer; }
    public void openOffer() { openedOffers++; offer = ""; }
    public void dismissOffer() { dismissedOffers++; offer = ""; }
}
