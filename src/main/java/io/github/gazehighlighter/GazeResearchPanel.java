package io.github.gazehighlighter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/** A single-path experiment wizard. Only the current step is exposed. */
public final class GazeResearchPanel extends JPanel {
    public enum Input {
        MOUSE("Mouse proxy"), WEBCAM("Webcam eye tracking"), SIMULATION("Mouse simulation (no camera)");
        final String title;
        Input(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }
    public enum Action { REPORT, EXPORT, WALKTHROUGH, TRACE, CONTEXT, AI_SETTINGS, ASK_AI, DEMO, GUIDE, REMOTE }
    public enum Step { INPUT, SCENARIO, SESSION, READY, RECORDING, RESULTS, PROFILE }
    public record SavedSession(String id, String title) { @Override public String toString() { return title; } }
    public record View(Input input, boolean paused, boolean running, boolean starting, boolean tracking,
                       boolean calibrated, boolean calibrating, boolean recording, boolean configurationLocked,
                       int eventCount, long durationMs, boolean hasSession, boolean exported,
                       GazeScenario scenario, GazeFeatureProfile features, GazeSessionMetadata.Labels labels,
                       String aiProvider, String target, String trackerMessage) {}
    public interface Controller {
        View view();
        void setInput(Input input);
        void setPaused(boolean paused);
        void stopCamera();
        void restart();
        void calibrate();
        void applyScenario(GazeScenario scenario);
        void applyFeatures(GazeFeatureProfile profile);
        boolean startSession(GazeSessionMetadata.Labels labels);
        void stopSession();
        void perform(Action action);
        default List<SavedSession> savedSessions() { return List.of(); }
        default boolean openSession(String id) { return false; }
        default String storageStatus() { return ""; }
        default String remoteStatus() { return "Remote upload off · recordings stay local."; }
        default String contextStatus() { return ""; }
        default boolean showCursor() { return false; }
        default void setShowCursor(boolean value) {}
        default GazeResponse response() { return GazeResponse.STEADY; }
        default void setResponse(GazeResponse response) {}
        default void checkAccuracy() {}
        default String calibrationSummary() { return ""; }
        default boolean hasPersonalProfile() { return false; }
        default boolean usesPersonalProfile() { return false; }
        default void usePersonalProfile(boolean use) {}
        default void resetPersonalProfile() {}
        default String personalProfileSummary() { return "No personal profile yet. Choose Personalization to create one for all scenarios."; }
        default boolean personalize(GazeSessionMetadata.Labels labels) { return false; }
        default GazeReviewPurpose reviewPurpose() { return GazeReviewPurpose.COMPARE; }
        default boolean collectBlinks() { return false; }
        default GazeBlinkMonitor.Config blinkConfig() { return new GazeBlinkMonitor.Config("","",false,null,null,false); }
        default void setBlinkOptions(boolean enabled,GazeBlinkMonitor.Config config) {}
        default String blinkStatus() { return GazeBlinkMonitor.status(null); }
        default boolean quietOffers() { return true; }
        default void setReviewOptions(GazeReviewPurpose purpose, boolean quiet) {}
        default String offerStatus() { return ""; }
        default void openOffer() {}
        default void dismissOffer() {}
    }
    private final Controller controller;
    private final Timer timer = new Timer(500, e -> refresh());
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private Step step = Step.INPUT;
    private boolean loading, inputCommitted, scenarioCommitted, wasRecording;
    private boolean browsingSaved;
    private List<SavedSession> knownSessions = List.of();
    private final JLabel progress = new JLabel(), status = new JLabel();
    private final JTextArea guidance = help("", 2), trackerHint = help("", 2), scenarioHelp = help("", 4), aiHint = help("", 3);
    private final JTextArea summary = help("", 7), live = help("", 4), result = help("", 4), storage = help("", 2);
    private final JTextArea contextStatus = help("", 4);
    private final JTextArea remoteSession = help("", 3), remoteReady = help("", 3), remoteLive = help("", 3), remoteResult = help("", 3);
    private final JTextArea calibrationSummary = help("", 3);
    private final JTextArea responseHint = help("Steady reduces jitter with a short delay; Responsive follows faster. Choose before recording. This does not detect physiological fixations or correct position bias.", 3);
    private final JToggleButton responseDetailsToggle = new JToggleButton("Response settings & personal profile (optional)");
    private final JPanel responseDetails = column();
    private final JTextArea activeResponse = help("", 2), readyHint = help("", 3);
    private final JLabel readyHeading = heading("4. Ready to record");
    private final JCheckBox inputCursor = check("Show gaze cursor (blue gutter dot)", "show-cursor-input");
    private final JCheckBox liveCursor = check("Show gaze cursor (blue gutter dot)", "show-cursor-live");
    private final JComboBox<GazeResponse> response = new JComboBox<>(GazeResponse.values());
    private final JCheckBox usePersonal = check("Use my personal response profile", "use-personal-input");
    private final JCheckBox sessionPersonal = check("Use my personal response profile", "use-personal-session");
    private final JTextArea profileHint = help("", 4), sessionProfileHint = help("", 3), savedProfile = help("", 6);
    private final JButton resetProfile;
    private final JComboBox<GazeReviewPurpose> purpose = new JComboBox<>(GazeReviewPurpose.values());
    private final JCheckBox quietOffers = check("Offer assistance quietly (30-second cooldown)", "quiet-offers");
    private final JPanel reviewOptions = column(), liveAi = column(), liveTour = column(), offerBox = column();
    private final JTextArea offerText = help("", 2), recordingInstructions = help("", 3);
    private final JComboBox<Input> input = new JComboBox<>(Input.values());
    private final JComboBox<GazeScenario> scenario = new JComboBox<>(GazeScenario.values());
    private final JComboBox<SavedSession> sessions = new JComboBox<>();
    private final JButton back = button("Back", "back", this::back);
    private final JButton browseSaved = button("Open saved recording…", "browse-saved", this::browseSaved);
    private final JButton next = button("Next: scenario", "next", this::next);
    private final JButton calibration, accuracy, restart, stopCamera, pause;
    private final JButton connect = button("Connect webcam", "connect-webcam", this::connectWebcam);
    private final JCheckBox focus = check("Focus on the viewed block", "focus");
    private final JCheckBox fade = check("Fade previously viewed blocks", "fade");
    private final JCheckBox names = check("Highlight matching names", "names");
    private final JCheckBox history = check("Use recent viewing history for AI", "history");
    private final JCheckBox ai = check("Prepare AI context after viewing a block", "ai");
    private final JSpinner stable = spinner(500, 200, 2000, 100, "stable-ms");
    private final JSpinner fadeAfter = spinner(1500, 500, 30000, 500, "fade-ms");
    private final JSpinner explainAfter = spinner(3000, 1000, 15000, 500, "explain-ms");
    private final JCheckBox blinkEnable = check("Collect webcam blink observations", "blink-enable"), blinkReference = check("Rested reference recording", "blink-reference"), blinkSuggestions = check("Show optional break suggestion", "blink-suggestions");
    private final JTextField blinkGroup = field("blink-group");
    private final JSpinner blinkFatigue = spinner(-1,-1,10,1,"blink-fatigue"), blinkEffort = spinner(-1,-1,10,1,"blink-effort");
    private final JTextArea blinkLive = help("",5), blinkResult = help("",5);
    private final JTextField participant = field("participant"), task = field("task"), condition = field("condition");
    private final JPanel features = column(), aiSetup = column(), labels = column();
    private final JTextArea provider = help("", 2);
    private final JButton report = action("Viewing report", "report", Action.REPORT);
    private final JButton export = action("Export recording…", "export", Action.EXPORT);
    private final JButton walkthrough = action("Review walkthrough", "walkthrough", Action.WALKTHROUGH);
    private final JButton trace = action("Replay viewing trace", "trace", Action.TRACE);

    public GazeResearchPanel(Controller controller) {
        super(new BorderLayout(0, 8)); this.controller = controller;
        resetProfile = button("Reset personal profile…", "reset-profile", () -> run(controller::resetPersonalProfile));
        calibration = button("Calibrate…", "calibrate", () -> run(controller::calibrate));
        accuracy = button("Check accuracy…", "check-accuracy", () -> run(controller::checkAccuracy));
        restart = button("Restart tracker", "restart", () -> run(controller::restart));
        stopCamera = button("Stop camera", "stop-camera", () -> { controller.stopCamera(); inputCommitted = false; refresh(); });
        pause = button("Pause recording", "processing", () -> run(() -> controller.setPaused(!controller.view().paused)));
        setBorder(new EmptyBorder(10, 10, 10, 10)); setMinimumSize(new Dimension(360, 300));
        JPanel header = column();
        JLabel title = new JLabel("Gaze Research"); title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        JPanel titleRow = new JPanel(new BorderLayout()); titleRow.add(title, BorderLayout.WEST);
        titleRow.add(action("Help", "getting-started", Action.GUIDE), BorderLayout.EAST);
        JPanel navigation = new JPanel(new BorderLayout(8, 0)); navigation.setBorder(new EmptyBorder(5, 0, 5, 0));
        navigation.add(back, BorderLayout.WEST); navigation.add(next, BorderLayout.CENTER);
        header.add(titleRow); header.add(progress); header.add(status); header.add(guidance); header.add(navigation); add(header, BorderLayout.NORTH);
        pages.add(scroll(inputPage()), Step.INPUT.name()); pages.add(scroll(scenarioPage()), Step.SCENARIO.name());
        pages.add(scroll(sessionPage()), Step.SESSION.name()); pages.add(scroll(readyPage()), Step.READY.name());
        pages.add(scroll(recordingPage()), Step.RECORDING.name()); pages.add(scroll(resultsPage()), Step.RESULTS.name());
        pages.add(scroll(profilePage()), Step.PROFILE.name());
        add(pages, BorderLayout.CENTER);
        next.setFont(next.getFont().deriveFont(Font.BOLD));
        input.setName("input"); scenario.setName("scenario"); sessions.setName("sessions"); scenarioHelp.setName("scenario-help");
        input.getAccessibleContext().setAccessibleName("Gaze input source");
        scenario.getAccessibleContext().setAccessibleName("Experiment scenario");
        sessions.getAccessibleContext().setAccessibleName("New or saved session");
        sessions.setPrototypeDisplayValue(new SavedSession("", "New or saved session"));
        View s = controller.view();
        response.setName("gaze-response"); calibrationSummary.setName("calibration-summary"); trackerHint.setName("tracker-status");
        response.setSelectedItem(controller.response());
        response.addActionListener(e -> { if (!loading) run(() -> controller.setResponse((GazeResponse) response.getSelectedItem())); });
        usePersonal.addActionListener(e -> { if (!loading) run(() -> controller.usePersonalProfile(usePersonal.isSelected())); });
        sessionPersonal.addActionListener(e -> { if (!loading) run(() -> controller.usePersonalProfile(sessionPersonal.isSelected())); });
        purpose.setName("review-purpose"); purpose.setSelectedItem(controller.reviewPurpose()); quietOffers.setSelected(controller.quietOffers());
        inputCursor.addActionListener(e -> run(() -> controller.setShowCursor(inputCursor.isSelected())));
        liveCursor.addActionListener(e -> run(() -> controller.setShowCursor(liveCursor.isSelected())));
        if (!s.recording && !s.configurationLocked) controller.setPaused(true);
        input.setSelectedItem(s.input); scenario.setSelectedItem(s.scenario);
        var eye=controller.blinkConfig();blinkEnable.setSelected(controller.collectBlinks() || s.scenario == GazeScenario.BLINK_BASELINE);blinkGroup.setText(eye.group());blinkReference.setSelected(eye.reference());blinkSuggestions.setSelected(eye.suggestions());
        blinkFatigue.setValue(eye.fatigue()==null?-1:eye.fatigue());blinkEffort.setValue(eye.effort()==null?-1:eye.effort());
        participant.setText(s.labels.participant()); task.setText(s.labels.task()); condition.setText(s.labels.condition());
        loadProfile(s.scenario == GazeScenario.CUSTOM ? s.features : s.scenario.profile()); updateScenario();
        input.addActionListener(e -> { if (!loading) { inputCommitted = false; scenarioCommitted = false; refresh(); } });
        scenario.addActionListener(e -> {
            if (!loading) { blinkEnable.setSelected(selectedScenario() == GazeScenario.BLINK_BASELINE); scenarioCommitted = false; if (selectedScenario() != GazeScenario.CUSTOM) loadProfile(selectedScenario().profile()); updateScenario(); refresh(); }
        });
        sessions.addActionListener(e -> { if (!loading) refresh(); });
        for (JCheckBox box : List.of(focus, fade, names, history, ai)) box.addActionListener(e -> { if (!loading) { scenarioCommitted = false; refresh(); } });
        for (JSpinner spinner : List.of(stable, fadeAfter, explainAfter)) spinner.addChangeListener(e -> { if (!loading) scenarioCommitted = false; });
        if (s.recording) { step = Step.RECORDING; wasRecording = true; }
        refresh();
    }
    private JPanel inputPage() {
        JPanel body = column(); body.add(heading("1. Choose input"));
        var welcome = help("Use viewing input to focus code, mark where you looked, or gather code for an AI question. First try? Mouse proxy works without a camera or AI account. Help above explains every task and the recorded data.", 4);
        welcome.setName("welcome"); body.add(welcome); body.add(input); body.add(inputCursor); body.add(connect); body.add(trackerHint);
        body.add(row(calibration, accuracy)); body.add(calibrationSummary); body.add(restart);
        body.add(activeResponse); body.add(responseDetailsToggle);
        responseDetails.setName("response-details"); responseDetailsToggle.setName("response-details-toggle");
        responseDetailsToggle.addActionListener(e -> { responseDetails.setVisible(responseDetailsToggle.isSelected()); revalidate(); repaint(); });
        responseDetails.add(help("Calibration aligns gaze with the screen. Personalization optionally chooses how steady or responsive tracking feels. Create a profile using the Personalization scenario.", 3));
        responseDetails.add(usePersonal); responseDetails.add(profileHint); responseDetails.add(resetProfile);
        responseDetails.add(response); responseDetails.add(responseHint); responseDetails.setVisible(false); body.add(responseDetails);
        body.add(help("Gaze effects stay paused until you start a session. The cursor is optional; hiding it keeps tracking active.", 2));
        body.add(browseSaved);
        return body;
    }
    private JPanel scenarioPage() {
        JPanel body = column(); body.add(heading("2. Choose scenario")); body.add(scenario); body.add(scenarioHelp);
        for (JCheckBox box : List.of(focus, fade, names, history, ai)) features.add(box);
        features.add(form("Stable target (ms)", stable)); features.add(form("Fade after (ms)", fadeAfter));
        features.add(form("AI block viewing (ms)", explainAfter));
        features.add(help("Next applies this combination. Defaults are 500 / 1500 / 3000 ms.", 2)); body.add(features);
        aiSetup.add(provider); aiSetup.add(action("Open JetBrains AI Chat", "ai-settings", Action.AI_SETTINGS));
        aiSetup.add(aiHint);
        reviewOptions.add(heading("Choose your question"));
        reviewOptions.add(purpose); reviewOptions.add(quietOffers);
        reviewOptions.add(help("Automatic preparation gathers code, not an AI answer. Offers stay in this panel; they never interrupt with a popup. Sending happens in native AI Chat.", 3));
        body.add(reviewOptions); body.add(aiSetup);
        body.add(action("Open example code", "demo", Action.DEMO));
        body.add(help("Optional practice: check whether a whitespace-only name should produce a Guest badge. Compare createBadge with isValidName.", 3));
        return body;
    }
    private JPanel sessionPage() {
        JPanel body = column(); body.add(heading("3. Choose session")); body.add(help("A session is one recording for a task. Create a new one, or reopen a saved recording to see its results.", 2)); body.add(sessions);
        labels.add(form("Participant ID", participant)); labels.add(form("Task / review goal", task)); labels.add(form("Condition", condition));
        labels.add(help("All fields are optional. Use a participant code. Next shows the setup summary before recording.", 3)); body.add(labels);
        body.add(blinkEnable);body.add(form("Camera/reference group",blinkGroup));body.add(blinkReference);
        body.add(form("Fatigue (0–10; −1 unreported)",blinkFatigue));body.add(form("Effort (0–10; −1 unreported)",blinkEffort));body.add(blinkSuggestions);
        body.add(help("Use the same participant code and camera group across recordings; blank participant means local-user. Change group when the setup changes. Only explicitly rested recordings build the reference. At least 3 usable minute windows and 10 blinks are needed for later recordings. No video is saved. Blink summaries enter explicit AI prompt previews when context is attached.",6));
        body.add(storage);
        body.add(sessionPersonal); body.add(sessionProfileHint);
        body.add(help("Saved sessions open their results without recording again. Full recordings contain source snapshots and save locally. Optional remote upload sends a separate reduced dataset.", 3));
        remoteSession.setName("remote-session-status"); body.add(remoteSession);
        body.add(action("Configure remote upload (optional)…", "remote-setup", Action.REMOTE));
        return body;
    }
    private JPanel readyPage() {
        JPanel body = column(); body.add(readyHeading); body.add(summary); body.add(readyHint);
        body.add(remoteReady);
        return body;
    }
    private JPanel recordingPage() {
        JPanel body = column(); body.add(heading("Recording")); body.add(live); body.add(liveCursor); body.add(row(pause, stopCamera));
        recordingInstructions.setName("recording-instructions"); body.add(recordingInstructions);
        blinkLive.setName("blink-live"); body.add(blinkLive); body.add(remoteLive);
        liveAi.setName("live-ai-controls"); contextStatus.setName("context-status"); liveAi.add(contextStatus);
        offerBox.setName("assistance-offer"); offerBox.setBorder(BorderFactory.createTitledBorder("Assistance available"));
        offerText.setName("offer-text"); offerBox.add(offerText);
        offerBox.add(button("Open offered question…", "open-offer", () -> run(controller::openOffer)));
        offerBox.add(button("Not now", "dismiss-offer", () -> run(controller::dismissOffer))); liveAi.add(offerBox);
        liveAi.add(action("Preview captured AI context…", "live-context", Action.CONTEXT));
        liveAi.add(action("Ask JetBrains AI about viewed code…", "ask-ai", Action.ASK_AI)); body.add(liveAi);
        liveTour.setName("live-walkthrough-controls"); liveTour.add(action("Open review walkthrough", "live-walkthrough", Action.WALKTHROUGH));
        liveTour.add(help("Look at code to select a walkthrough step. Add findings to your notes; the viewing trace is separate. Stop recording above to inspect results.", 4)); body.add(liveTour);
        return body;
    }
    private JPanel profilePage() {
        JPanel body = column(); body.add(heading("Personal profile saved")); body.add(savedProfile);
        body.add(help("Your profile is selected for every scenario. Choose another scenario to try it, or turn it off in Input or Session to compare with standard settings. Update it by running Personalization again. No review recording was created by this setup exercise.", 5)); return body;
    }
    private JPanel resultsPage() {
        JPanel body = column(); body.add(heading("Session results")); body.add(result); body.add(row(report, export));
        blinkResult.setName("blink-results");body.add(blinkResult);
        remoteResult.setName("remote-results-status"); body.add(remoteResult);
        body.add(action("Remote upload: preview, send or configure…", "remote-results", Action.REMOTE));
        body.add(walkthrough); body.add(help("Review walkthrough: overview → files → code, with notes and explicit review marks. Gaze selects code; Ask JetBrains AI opens a prompt preview, then native AI Chat.", 4));
        body.add(trace); body.add(help("Viewing trace: chronological replay of where you looked, including revisits. It shows exposure; it does not explain intent or prove understanding.", 4));
        body.add(action("Inspect AI context for this session…", "context", Action.CONTEXT));
        body.add(help("Shows a locally prepared prompt when retained. For reopened recordings, reconstructs a draft from captured source and labels it clearly. Inspecting never sends a request.", 4));
        return body;
    }
    public Step currentStep() { return step; }
    public void startRefreshing() { timer.start(); }
    public void dispose() { timer.stop(); }
    public void refresh() {
        View s = controller.view();
        if (inputCommitted && s.input != input.getSelectedItem()) inputCommitted = false;
        if (scenarioCommitted && !s.features.equals(selectedScenario() == GazeScenario.CUSTOM ? profile() : selectedScenario().profile())) scenarioCommitted = false;
        if (s.recording) { step = Step.RECORDING; wasRecording = true; }
        else if (wasRecording) { step = Step.RESULTS; wasRecording = false; resetBlinkRatings(); }
        cards.show(pages, step.name());
        progress.setText(switch (step) {
            case INPUT -> "1 Input → 2 Scenario → 3 Session → 4 Record";
            case SCENARIO -> "✓ Input → 2 Scenario → 3 Session → 4 Record";
            case SESSION -> "✓ Input → ✓ Scenario → 3 Session → 4 Record";
            case READY -> "✓ Input → ✓ Scenario → ✓ Session → 4 Record";
            case RECORDING -> "Experiment in progress";
            case RESULTS -> "Recording finished";
            case PROFILE -> "Personalization complete";
        });
        status.setText(s.input.title + " · " + (s.recording ? (s.paused ? "PAUSED · " : "REC · ") + duration(s.durationMs) : "Not recording"));
        guidance.setText(switch (step) {
            case INPUT -> "Choose the source of your viewing input.";
            case SCENARIO -> selectedScenario() == GazeScenario.PERSONALIZATION && (s.input != Input.WEBCAM || !s.calibrated)
                    ? "Personalization needs a calibrated webcam. Go Back to Input to connect and calibrate, or choose another scenario."
                    : "A scenario is what the plugin does with your viewing input. Choose one task.";
            case SESSION -> "Create a session or reopen a saved recording.";
            case READY -> ready(s) && inputCommitted && scenarioCommitted ? "Check the setup, then start with one click." : "Setup changed or input is not ready. Go Back to confirm it or finish calibration.";
            case RECORDING -> s.paused ? "Recording paused. Resume when ready; the camera stays on." : "Read code normally. Stop when the task is finished.";
            case RESULTS -> controller.storageStatus();
            case PROFILE -> "Your response profile is ready to use in another scenario.";
        });
        boolean webcamSelected = input.getSelectedItem() == Input.WEBCAM;
        trackerHint.setText(input.getSelectedItem() == Input.MOUSE ? "Your pointer stands in for gaze. No camera needed. Click Next: scenario above."
                : input.getSelectedItem() == Input.SIMULATION ? "Test screen routing using your pointer; no camera or eye tracking. Click Next: scenario above."
                : s.input != Input.WEBCAM || !s.running && !s.starting ? (s.trackerMessage.startsWith("Returned to mouse proxy:") ? s.trackerMessage + " Retry with Connect webcam, or choose Mouse proxy." : "Click Connect webcam below the input selector.")
                : s.calibrating ? "Follow the calibration targets; then return here."
                : s.starting ? s.trackerMessage + " Calibrate becomes available after valid gaze samples arrive."
                : s.tracking ? "Webcam is receiving gaze. " + (s.calibrated ? "Calibration saved. " : "Click Calibrate… before a study. ") + "Click Next: scenario above to continue."
                : s.trackerMessage + " Next: scenario becomes available after valid gaze samples arrive.");
        connect.setVisible(webcamSelected);
        connect.setEnabled(!s.configurationLocked && !s.calibrating && (s.input != Input.WEBCAM || !s.running && !s.starting));
        calibration.setEnabled(!s.configurationLocked && s.input == Input.WEBCAM && s.tracking && !s.calibrating);
        calibration.setToolTipText(s.tracking ? "Follow nine screen targets, then check accuracy on five new targets."
                : "Requires valid webcam gaze samples. See the connection status above; retry if startup failed.");
        calibration.setVisible(input.getSelectedItem() == Input.WEBCAM);
        accuracy.setVisible(webcamSelected);
        accuracy.setEnabled(!s.configurationLocked && s.input == Input.WEBCAM && s.tracking && s.calibrated && !s.calibrating);
        calibrationSummary.setVisible(webcamSelected); calibrationSummary.setText(controller.calibrationSummary());
        loading = true;
        inputCursor.setSelected(controller.showCursor()); liveCursor.setSelected(controller.showCursor());
        response.setSelectedItem(controller.response()); loading = false;
        usePersonal.setSelected(controller.usesPersonalProfile()); sessionPersonal.setSelected(controller.usesPersonalProfile());
        usePersonal.setVisible(webcamSelected); profileHint.setVisible(webcamSelected); resetProfile.setVisible(webcamSelected && controller.hasPersonalProfile());
        usePersonal.setEnabled(controller.hasPersonalProfile() && !s.configurationLocked && !s.calibrating);
        sessionPersonal.setVisible(s.input == Input.WEBCAM); sessionProfileHint.setVisible(s.input == Input.WEBCAM);
        sessionPersonal.setEnabled(controller.hasPersonalProfile() && !s.configurationLocked && !s.calibrating);
        profileHint.setText(controller.personalProfileSummary()); sessionProfileHint.setText(controller.personalProfileSummary()); savedProfile.setText(controller.personalProfileSummary());
        activeResponse.setVisible(webcamSelected); activeResponse.setText("Response: " + (controller.usesPersonalProfile() ? "your personal profile" : "standard · " + controller.response()));
        responseDetailsToggle.setVisible(webcamSelected); responseDetails.setVisible(webcamSelected && responseDetailsToggle.isSelected());
        resetProfile.setEnabled(!s.configurationLocked && !s.calibrating);
        responseHint.setVisible(webcamSelected);
        response.setVisible(webcamSelected); response.setEnabled(!s.configurationLocked && !s.calibrating && !controller.usesPersonalProfile());
        inputCursor.setEnabled(!s.calibrating); liveCursor.setEnabled(!s.calibrating);
        restart.setEnabled(!s.configurationLocked && s.input != Input.MOUSE && !s.calibrating);
        restart.setVisible(input.getSelectedItem() != Input.MOUSE);
        input.setEnabled(!s.configurationLocked); scenario.setEnabled(!s.configurationLocked);
        for(JComponent c:List.of(blinkEnable,blinkReference,blinkSuggestions,blinkGroup,blinkFatigue,blinkEffort)) c.setEnabled(!s.configurationLocked);
        blinkLive.setText(controller.blinkStatus());blinkResult.setText(controller.blinkStatus());
        purpose.setEnabled(!s.configurationLocked); quietOffers.setEnabled(!s.configurationLocked);
        provider.setText(s.aiProvider); provider.setToolTipText(s.aiProvider);
        List<SavedSession> available = controller.savedSessions();
        browseSaved.setEnabled(!available.isEmpty() && !s.configurationLocked);
        if (!available.equals(knownSessions)) {
            loading = true; Object selected = sessions.getSelectedItem(); sessions.removeAllItems();
            sessions.addItem(new SavedSession("", "New session")); available.forEach(sessions::addItem);
            if (selected != null && (available.contains(selected) || ((SavedSession) selected).id.isEmpty())) sessions.setSelectedItem(selected);
            knownSessions = List.copyOf(available); loading = false;
        } else if (sessions.getItemCount() == 0) sessions.addItem(new SavedSession("", "New session"));
        boolean fresh = selectedSession().id.isEmpty();
        for (Component c : labels.getComponents()) c.setVisible(fresh); labels.setVisible(fresh);
        storage.setText(controller.storageStatus());
        String remote = controller.remoteStatus();
        for (var area : List.of(remoteSession, remoteReady, remoteLive, remoteResult)) area.setText(remote);
        boolean personalizing = selectedScenario() == GazeScenario.PERSONALIZATION;
        readyHeading.setText(personalizing ? "4. Ready to personalize" : "4. Ready to record");
        readyHint.setText(personalizing ? "Start personalization opens the guided target task. Save the suggested response profile, then choose another scenario to use it. It does not create a review recording."
                : "Start recording starts viewing effects and recording together. Return to the code editor, then follow your scenario's instructions. Setup time is excluded.");
        summary.setText("Input: " + s.input.title + (s.input == Input.WEBCAM ? "\nResponse: " + (controller.usesPersonalProfile() ? "Personal profile" : "Standard · " + controller.response()) + "\n" + controller.calibrationSummary() : "")
                + "\nGaze cursor: " + (controller.showCursor() ? "visible" : "hidden") + "\nScenario: " + s.scenario.title + "\nParticipant: " + label(participant.getText())
                + "\nBlink collection: " + (blinkEnable.isSelected() ? "on · " + blinkGroup.getText() + (blinkReference.isSelected() ? " · rested reference" : "") : "off")
                + "\nTask: " + label(task.getText()) + "\nCondition: " + label(condition.getText()) + "\nAI: " + (s.features.explanations() ? controller.reviewPurpose().title + "; automatic context; " + (controller.quietOffers() ? "quiet offers" : "offers off") + "; send manually in native chat · " + s.aiProvider
                : s.scenario == GazeScenario.WALKTHROUGH ? "On request in JetBrains AI Chat · " + s.aiProvider : "No context prepared automatically"));
        live.setText(s.eventCount + " recorded code-line visits\n" + s.target + "\nInput: " + s.input.title
                + (s.input == Input.WEBCAM ? "\nResponse: " + (controller.usesPersonalProfile() ? "Personal profile" : controller.response()) : "")
                + (s.features.explanations() ? "\nQuestion: " + controller.reviewPurpose().title : ""));
        contextStatus.setText(controller.contextStatus());
        liveAi.setVisible(s.features.explanations()); liveTour.setVisible(s.scenario == GazeScenario.WALKTHROUGH);
        recordingInstructions.setText(s.scenario.goal + "\n" + s.scenario.liveInstructions());
        String offered = s.features.explanations() ? controller.offerStatus() : ""; offerText.setText(offered); offerBox.setVisible(!offered.isEmpty());
        result.setText(s.eventCount + " recorded line visits · " + duration(s.durationMs) + "\n" + s.labels.summary()
                + (s.exported ? "\nExported." : "\nExport a copy to keep with your study files."));
        pause.setText(s.paused ? "Resume recording" : "Pause recording"); stopCamera.setEnabled(s.input != Input.MOUSE);
        stopCamera.setText(s.input == Input.SIMULATION ? "Stop simulation" : "Stop camera");
        report.setEnabled(s.hasSession); export.setEnabled(s.hasSession); trace.setEnabled(s.hasSession);
        walkthrough.setEnabled(s.hasSession);
        back.setVisible(step != Step.RECORDING && step != Step.RESULTS && step != Step.PROFILE); back.setEnabled(step != Step.INPUT && !s.configurationLocked);
        next.setText(switch (step) {
            case INPUT -> "Next: scenario";
            case SCENARIO -> "Next: session";
            case SESSION -> fresh ? browsingSaved ? "Set up new session" : "Next: review setup" : "Open saved results";
            case READY -> selectedScenario() == GazeScenario.PERSONALIZATION ? "Start personalization" : "Start recording";
            case RECORDING -> "Stop recording";
            case RESULTS -> "New experiment";
            case PROFILE -> "Choose another scenario";
        });
        next.setEnabled(step == Step.RECORDING || step == Step.RESULTS || !s.configurationLocked && switch (step) {
            case INPUT -> input.getSelectedItem() != Input.WEBCAM || s.input == Input.WEBCAM && ready(s);
            case SCENARIO -> selectedScenario() != GazeScenario.PERSONALIZATION && selectedScenario() != GazeScenario.BLINK_BASELINE || s.input == Input.WEBCAM && s.calibrated && ready(s);
            case READY -> ready(s) && inputCommitted && scenarioCommitted && (selectedScenario() != GazeScenario.PERSONALIZATION || s.input == Input.WEBCAM && s.calibrated) && (!blinkEnable.isSelected() || s.input == Input.WEBCAM);
            default -> true;
        });
        revalidate(); repaint();
    }
    private boolean ready(View s) { return !s.starting && !s.calibrating && (s.input != Input.WEBCAM || s.tracking); }
    private SavedSession selectedSession() { return sessions.getSelectedItem() instanceof SavedSession s ? s : new SavedSession("", "New session"); }
    private void browseSaved() {
        browsingSaved = true; step = Step.SESSION; refresh();
        if (sessions.getItemCount() > 1) sessions.setSelectedIndex(1);
        refresh();
    }
    private void connectWebcam() {
        controller.setPaused(true); controller.setInput(Input.WEBCAM); inputCommitted = false; refresh();
    }
    private void next() {
        View s = controller.view();
        switch (step) {
            case INPUT -> {
                controller.setPaused(true); controller.setInput((Input) input.getSelectedItem()); inputCommitted = true;
                if (ready(controller.view())) step = Step.SCENARIO;
            }
            case SCENARIO -> {
                controller.setReviewOptions((GazeReviewPurpose) purpose.getSelectedItem(), quietOffers.isSelected());
                if (selectedScenario() == GazeScenario.CUSTOM) controller.applyFeatures(profile()); else controller.applyScenario(selectedScenario());
                scenarioCommitted = true; step = Step.SESSION; browsingSaved = false;
                if (condition.getText().isBlank()) condition.setText(selectedScenario().title);
            }
            case SESSION -> {
                if (selectedSession().id.isEmpty()) step = browsingSaved ? Step.INPUT : Step.READY;
                else if (controller.openSession(selectedSession().id)) step = Step.RESULTS;
            }
            case READY -> {
                controller.setBlinkOptions(blinkEnable.isSelected(),new GazeBlinkMonitor.Config(participant.getText(),blinkGroup.getText(),blinkReference.isSelected(),(int)blinkFatigue.getValue(),(int)blinkEffort.getValue(),blinkSuggestions.isSelected()));
                if (inputCommitted && scenarioCommitted && ready(s) && selectedScenario() == GazeScenario.PERSONALIZATION) {
                    if (controller.personalize(new GazeSessionMetadata.Labels(participant.getText(), task.getText(), condition.getText()))) step = Step.PROFILE;
                } else if (inputCommitted && scenarioCommitted && ready(s) && controller.startSession(new GazeSessionMetadata.Labels(participant.getText(), task.getText(), condition.getText()))) {
                    step = Step.RECORDING; wasRecording = true;
                }
            }
            case RECORDING -> { controller.stopSession(); step = Step.RESULTS; wasRecording = false; resetBlinkRatings(); }
            case RESULTS -> { if (!s.configurationLocked) controller.setPaused(true); step = Step.INPUT; inputCommitted = false; scenarioCommitted = false; sessions.setSelectedIndex(0); }
            case PROFILE -> { step = Step.SCENARIO; scenarioCommitted = false; condition.setText(""); }
        }
        refresh();
    }
    private void resetBlinkRatings() { blinkReference.setSelected(false);blinkFatigue.setValue(-1);blinkEffort.setValue(-1); }
    private void back() {
        step = switch (step) { case SCENARIO -> Step.INPUT; case SESSION -> browsingSaved ? Step.INPUT : Step.SCENARIO; case READY -> Step.SESSION; default -> step; }; refresh();
    }
    private GazeScenario selectedScenario() { return (GazeScenario) scenario.getSelectedItem(); }
    private void updateScenario() {
        GazeScenario s = selectedScenario(); scenarioHelp.setText(s.goal + "\n\nTry this: " + s.instructions);
        features.setVisible(s == GazeScenario.CUSTOM); aiSetup.setVisible(s.requiresAI() || s == GazeScenario.CUSTOM || s == GazeScenario.WALKTHROUGH);
        reviewOptions.setVisible(s == GazeScenario.RELATIONSHIPS || s == GazeScenario.CUSTOM);
        aiHint.setText(s == GazeScenario.WALKTHROUGH
                ? "Optional: enable/sign in to JetBrains AI Assistant or Junie. Ask previews your question; copy/paste/send it in native chat. Add findings to your notes."
                : "Enable/sign in to JetBrains AI Assistant or Junie. Questions are prepared locally. Preview, then copy/paste/send in native chat. Choose the assistant and model there.");
    }
    private void loadProfile(GazeFeatureProfile p) {
        loading = true; focus.setSelected(p.focus()); fade.setSelected(p.fadeViewed()); names.setSelected(p.relationships());
        history.setSelected(p.historyContext()); ai.setSelected(p.explanations());
        stable.setValue(p.stableMs()); fadeAfter.setValue(p.fadeAfterMs()); explainAfter.setValue(p.explainAfterMs()); loading = false;
    }
    private GazeFeatureProfile profile() {
        return new GazeFeatureProfile(focus.isSelected(), fade.isSelected(), names.isSelected(), history.isSelected(), ai.isSelected(),
                (int) stable.getValue(), (int) fadeAfter.getValue(), (int) explainAfter.getValue());
    }
    private static String label(String s) { return s.isBlank() ? "not set" : s.strip(); }
    private static JLabel heading(String title) { JLabel label = new JLabel(title); label.setFont(label.getFont().deriveFont(Font.BOLD, 16f)); return label; }
    private void run(Runnable action) { action.run(); refresh(); }
    private JButton action(String title, String name, Action action) { return button(title, name, () -> run(() -> controller.perform(action))); }
    private static JButton button(String text, String name, Runnable action) {
        JButton button = new JButton(text); button.setName(name); button.addActionListener(e -> action.run()); return button;
    }
    private static JCheckBox check(String title, String name) { JCheckBox c = new JCheckBox(title); c.setName(name); return c; }
    private static JTextField field(String name) { JTextField f = new JTextField(12); f.setName(name); return f; }
    private static JSpinner spinner(int n, int min, int max, int step, String name) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(n, min, max, step)); s.setName(name); return s;
    }
    static JPanel column() {
        return new JPanel() {
            { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            @Override public Component add(Component component) {
                if (component instanceof JComponent c) c.setAlignmentX(LEFT_ALIGNMENT);
                return super.add(component);
            }
        };
    }
    private static JPanel section(String title) {
        JPanel panel = column(); panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title), new EmptyBorder(6, 8, 8, 8))); return panel;
    }
    static JPanel row(JComponent left, JComponent right) {
        JPanel row = new JPanel(new GridLayout(1, 2, 6, 0)); row.setBorder(new EmptyBorder(5, 0, 5, 0)); row.add(left); row.add(right); return row;
    }
    private static JPanel form(String title, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(8, 0)); JLabel label = new JLabel(title); label.setLabelFor(control);
        label.setPreferredSize(new Dimension(Math.max(label.getFontMetrics(label.getFont()).stringWidth("Participant ID (optional)"),
                label.getPreferredSize().width), label.getPreferredSize().height));
        row.setBorder(new EmptyBorder(3, 0, 3, 0)); row.add(label, BorderLayout.WEST); row.add(control, BorderLayout.CENTER); return row;
    }
    static JTextArea help(String text, int rows) {
        JTextArea area = new JTextArea(text, rows, 1) {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                // Wrapped instructions grow with their content at the actual tool-window width.
                // Fixed row counts alone clip longer scenario help in a narrow docked panel.
                if (getWidth() > 0 && getUI() != null) {
                    Insets insets = getInsets();
                    var view = getUI().getRootView(this);
                    view.setSize(Math.max(1, getWidth() - insets.left - insets.right), Integer.MAX_VALUE);
                    size.height = Math.max(size.height, (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS))
                            + insets.top + insets.bottom);
                }
                return size;
            }
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
            @Override public void setBounds(int x, int y, int width, int height) {
                boolean resized = width != getWidth();
                super.setBounds(x, y, width, height);
                if (resized) revalidate();
            }
        }; area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setEditable(false); area.setOpaque(false); area.setFont(UIManager.getFont("Label.font"));
        area.setForeground(UIManager.getColor("Label.foreground")); area.setBorder(new EmptyBorder(5, 0, 5, 0)); return area;
    }
    static JScrollPane scroll(JPanel body) {
        JPanel wrapper = new ScrollContent(); wrapper.add(body, BorderLayout.NORTH);
        JScrollPane pane = new JScrollPane(wrapper, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setBorder(new EmptyBorder(4, 0, 0, 0)); pane.getVerticalScrollBar().setUnitIncrement(18); return pane;
    }
    private static final class ScrollContent extends JPanel implements Scrollable {
        ScrollContent() { super(new BorderLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(380, 500); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(18, visible.height - 18);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
    static String duration(long ms) { return String.format("%02d:%02d", ms / 60000, ms / 1000 % 60); }
}
