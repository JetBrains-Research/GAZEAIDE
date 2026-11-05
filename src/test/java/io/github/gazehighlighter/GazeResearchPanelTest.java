package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.gazehighlighter.GazeResearchPanel.Step.*;

class GazeResearchPanelTest {
    @Test void pendingWebcamShowsActualStartupStageAndKeepsCalibrationDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); c.input = GazeResearchPanel.Input.WEBCAM; c.starting = true;
            c.trackerMessage = GazeTrackerStartup.Stage.BROWSER.message;
            var panel = new GazeResearchPanel(c);
            assertTrue(component(panel, "tracker-status", JTextArea.class).getText().contains("JCEF"));
            assertFalse(button(panel, "calibrate").isEnabled()); assertFalse(button(panel, "next").isEnabled());
            c.starting = false; c.running = c.tracking = true; panel.refresh();
            assertTrue(button(panel, "calibrate").isEnabled()); panel.dispose();
        });
    }
    @Test void failedConnectionKeepsTheCauseVisibleAndAllowsRetry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            combo(panel, "input").setSelectedItem(GazeResearchPanel.Input.WEBCAM);
            c.trackerMessage = "Returned to mouse proxy: " + GazeTrackerStartup.Stage.BROWSER.failure;
            panel.refresh();
            assertTrue(component(panel, "tracker-status", JTextArea.class).getText().contains("JCEF"));
            assertTrue(button(panel, "connect-webcam").isEnabled()); assertFalse(button(panel, "calibrate").isEnabled());
            panel.dispose();
        });
    }
    @Test void personalizationSavesAReusableProfileWithoutStartingAReviewRecording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); c.input = GazeResearchPanel.Input.WEBCAM;
            c.running = c.tracking = c.calibrated = true;
            var panel = new GazeResearchPanel(c); next(panel);
            combo(panel, "scenario").setSelectedItem(GazeScenario.PERSONALIZATION); next(panel); next(panel);
            assertEquals("Start personalization", button(panel, "next").getText()); next(panel);
            assertEquals(PROFILE, panel.currentStep()); assertEquals(1, c.personalizations); assertFalse(c.recording); assertTrue(c.personalActive);
            GazeResearchPanelPreview.prepareLayout(panel, 380, 760);
            assertTrue(button(panel, "next").getWidth() >= button(panel, "next").getPreferredSize().width, "Completion action must be fully readable");
            next(panel); assertEquals(SCENARIO, panel.currentStep());
            combo(panel, "scenario").setSelectedItem(GazeScenario.COVERAGE); next(panel);
            assertTrue(component(panel, "use-personal-session", JCheckBox.class).isSelected());
            component(panel, "use-personal-session", JCheckBox.class).doClick(); assertFalse(c.personalActive); assertTrue(c.personalProfile);
            component(panel, "use-personal-session", JCheckBox.class).doClick(); next(panel); next(panel);
            assertEquals(RECORDING, panel.currentStep()); assertTrue(c.personalActive);
            assertFalse(component(panel, "use-personal-session", JCheckBox.class).isEnabled()); panel.dispose();
        });
    }
    @Test void personalizationRequiresRealCalibratedWebcamAndCancellationRetainsSetup() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c); next(panel);
            combo(panel, "scenario").setSelectedItem(GazeScenario.PERSONALIZATION);
            assertFalse(button(panel, "next").isEnabled());
            c.input = GazeResearchPanel.Input.SIMULATION; c.tracking = c.calibrated = true; panel.refresh();
            assertFalse(button(panel, "next").isEnabled()); panel.dispose();
            c = new ResearchPanelTestController(); c.input = GazeResearchPanel.Input.WEBCAM; c.running = c.tracking = c.calibrated = true;
            c.allowPersonalization = false; c.personalProfile = c.personalActive = true;
            panel = new GazeResearchPanel(c); next(panel); combo(panel, "scenario").setSelectedItem(GazeScenario.PERSONALIZATION);
            next(panel); next(panel); next(panel);
            assertEquals(READY, panel.currentStep()); assertFalse(c.recording); assertTrue(c.personalActive); panel.dispose();
        });
    }
    @Test void purposeAndQuietOfferControlsAreAppliedOnlyWhenContinuingAndNeverOpenAiAutomatically() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c); next(panel);
            combo(panel, "scenario").setSelectedItem(GazeScenario.RELATIONSHIPS);
            combo(panel, "review-purpose").setSelectedItem(GazeReviewPurpose.REVIEW); panel.refresh();
            assertEquals(GazeReviewPurpose.COMPARE, c.purpose); next(panel); assertEquals(GazeReviewPurpose.REVIEW, c.purpose);
            next(panel); next(panel); c.offer = "Review for possible problems · caller [MOUSE_PROXY]"; panel.refresh();
            assertTrue(component(panel, "assistance-offer", JPanel.class).isVisible()); assertTrue(c.actions.isEmpty());
            button(panel, "dismiss-offer").doClick(); assertEquals(1, c.dismissedOffers); assertTrue(c.recording);
            c.offer = "Another offer"; panel.refresh(); button(panel, "open-offer").doClick(); assertEquals(1, c.openedOffers); assertTrue(c.recording);
            assertFalse(component(panel, "live-walkthrough-controls", JPanel.class).isVisible()); panel.dispose();
        });
    }
    @Test void visualAndBaselineScenariosShowOnlyRelevantRecordingControls() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var scenario : List.of(GazeScenario.FOCUS, GazeScenario.FADE, GazeScenario.NAMES, GazeScenario.COVERAGE, GazeScenario.OBSERVE, GazeScenario.WALKTHROUGH)) {
                var c = new ResearchPanelTestController(); c.scenario = scenario; c.profile = scenario.profile();
                var panel = new GazeResearchPanel(c); next(panel); next(panel); next(panel); next(panel);
                assertFalse(component(panel, "live-ai-controls", JPanel.class).isVisible());
                assertEquals(scenario == GazeScenario.WALKTHROUGH, component(panel, "live-walkthrough-controls", JPanel.class).isVisible());
                assertTrue(component(panel, "recording-instructions", JTextArea.class).getText().contains(scenario.goal)); panel.dispose();
            }
        });
    }
    @Test void helpIsAvailableWithoutStartingOrChangingTheSetupAndExplainsEveryScenario() throws Exception {
        String guide = GazeHelpDialog.guide();
        for (var scenario : GazeScenario.values()) assertTrue(guide.contains(scenario.title));
        assertTrue(guide.contains("NO CAMERA OR AI ACCOUNT NEEDED")); assertTrue(guide.contains("Calibration:"));
        assertTrue(guide.contains("Personalization:")); assertTrue(guide.contains("not physiological fixations"));
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            button(panel, "getting-started").doClick(); assertEquals(List.of(GazeResearchPanel.Action.GUIDE), c.actions);
            assertEquals(INPUT, panel.currentStep()); assertFalse(c.recording); panel.dispose();
        });
    }
    @Test void cursorCanBeToggledLiveWithoutStoppingOrPausingAndResponseIsLocked() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            assertFalse(component(panel, "show-cursor-input", JCheckBox.class).isSelected());
            component(panel, "show-cursor-input", JCheckBox.class).doClick(); assertTrue(c.cursor);
            next(panel); next(panel); next(panel); next(panel);
            assertTrue(component(panel, "show-cursor-live", JCheckBox.class).isSelected());
            component(panel, "show-cursor-live", JCheckBox.class).doClick();
            assertFalse(c.cursor); assertTrue(c.recording); assertFalse(c.paused); assertEquals(RECORDING, panel.currentStep());
            assertFalse(combo(panel, "gaze-response").isEnabled()); panel.dispose();
        });
    }
    @Test void webcamResponseAndAccuracyCheckAreAvailableBeforeRecording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            combo(panel, "input").setSelectedItem(GazeResearchPanel.Input.WEBCAM);
            button(panel, "connect-webcam").doClick(); c.tracking = true; c.calibrated = true; panel.refresh();
            combo(panel, "gaze-response").setSelectedItem(GazeResponse.BALANCED);
            assertEquals(GazeResponse.BALANCED, c.response);
            assertTrue(button(panel, "check-accuracy").isEnabled()); button(panel, "check-accuracy").doClick();
            assertEquals(1, c.accuracyChecks); assertFalse(c.recording);
            assertTrue(component(panel, "calibration-summary", JTextArea.class).getText().contains("35 px"));
            panel.dispose();
        });
    }
    @Test void followsInputScenarioSessionThenOneClickRecordingAndResults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            assertEquals(INPUT, panel.currentStep()); assertFalse(c.recording);
            next(panel); assertEquals(SCENARIO, panel.currentStep()); assertTrue(c.paused);
            combo(panel, "scenario").setSelectedItem(GazeScenario.NAMES);
            assertEquals(GazeScenario.FOCUS, c.scenario); // Draft does not change running configuration.
            next(panel); assertEquals(SESSION, panel.currentStep()); assertEquals(GazeScenario.NAMES, c.scenario);
            component(panel, "participant", JTextField.class).setText("P07");
            component(panel, "task", JTextField.class).setText("validator boundary");
            next(panel); assertEquals(READY, panel.currentStep()); assertFalse(c.recording); assertTrue(c.paused);
            assertEquals("Start recording", button(panel, "next").getText());
            next(panel); assertEquals(RECORDING, panel.currentStep()); assertTrue(c.recording); assertFalse(c.paused);
            assertEquals("P07", c.labels.participant()); assertEquals("validator boundary", c.labels.task());
            assertFalse(combo(panel, "input").isEnabled());
            next(panel); assertEquals(RESULTS, panel.currentStep()); assertTrue(c.paused);
            assertTrue(button(panel, "export").isEnabled()); button(panel, "export").doClick(); assertTrue(c.exported);
            next(panel); assertEquals(INPUT, panel.currentStep()); assertFalse(c.recording); panel.dispose();
        });
    }
    @Test void webcamConnectWaitsForSamplesAndCalibrationBeforeContinuing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            combo(panel, "input").setSelectedItem(GazeResearchPanel.Input.WEBCAM);
            assertEquals(GazeResearchPanel.Input.MOUSE, c.input);
            assertEquals("Next: scenario", button(panel, "next").getText());
            assertFalse(button(panel, "next").isEnabled());
            button(panel, "connect-webcam").doClick();
            assertEquals(INPUT, panel.currentStep()); assertTrue(c.running); assertFalse(button(panel, "next").isEnabled());
            c.tracking = true; panel.refresh(); assertTrue(button(panel, "calibrate").isEnabled());
            button(panel, "calibrate").doClick(); assertFalse(button(panel, "next").isEnabled());
            c.calibrating = false; c.calibrated = true; panel.refresh(); next(panel);
            assertEquals(SCENARIO, panel.currentStep()); assertTrue(c.paused); assertFalse(c.recording); panel.dispose();
        });
    }
    @Test void reopeningSavedSessionDoesNotStartRecording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); c.saved.add(new GazeResearchPanel.SavedSession("old", "P02 · validation"));
            var panel = new GazeResearchPanel(c); next(panel); next(panel);
            combo(panel, "sessions").setSelectedIndex(1); next(panel);
            assertEquals(RESULTS, panel.currentStep()); assertEquals("old", c.opened);
            assertFalse(c.recording); assertTrue(button(panel, "report").isEnabled()); panel.dispose();
        });
    }
    @Test void savedRecordingShortcutBypassesWebcamConnectionAndPreselectsNewestRecording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); c.input = GazeResearchPanel.Input.WEBCAM;
            c.saved.add(new GazeResearchPanel.SavedSession("old", "P02 · validation"));
            var panel = new GazeResearchPanel(c); button(panel, "browse-saved").doClick();
            assertEquals(SESSION, panel.currentStep()); assertEquals(1, combo(panel, "sessions").getSelectedIndex());
            next(panel); assertEquals(RESULTS, panel.currentStep()); assertEquals("old", c.opened);
            assertFalse(c.tracking); assertFalse(c.recording); panel.dispose();
        });
    }
    @Test void cancelledStartStaysAtReviewAndDoesNotResumeProcessing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); c.allowStart = false;
            var panel = new GazeResearchPanel(c); next(panel); next(panel); next(panel); next(panel);
            assertEquals(READY, panel.currentStep()); assertFalse(c.recording); assertTrue(c.paused); panel.dispose();
        });
    }
    @Test void inputOrScenarioChangedOutsideWizardRequiresReconfirmation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            next(panel); next(panel); next(panel);
            c.input = GazeResearchPanel.Input.SIMULATION; panel.refresh();
            assertFalse(button(panel, "next").isEnabled()); assertFalse(c.recording); panel.dispose();
        });
    }
    @Test void customFeatureDraftSurvivesPollingAndIsAppliedOnNext() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c); next(panel);
            combo(panel, "scenario").setSelectedItem(GazeScenario.CUSTOM);
            component(panel, "names", JCheckBox.class).doClick(); panel.refresh();
            assertTrue(component(panel, "names", JCheckBox.class).isSelected()); assertFalse(c.profile.relationships());
            next(panel); assertTrue(c.profile.relationships()); assertEquals(GazeScenario.CUSTOM, c.scenario); panel.dispose();
        });
    }
    @Test void savedCustomFeaturesAreOfferedWithoutReplacingThemWithAPreset() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); c.scenario = GazeScenario.CUSTOM;
            c.profile = new GazeFeatureProfile(false, true, true, false, false, 700, 2500, 4000);
            var panel = new GazeResearchPanel(c); next(panel);
            assertEquals(GazeScenario.CUSTOM, combo(panel, "scenario").getSelectedItem());
            assertTrue(component(panel, "names", JCheckBox.class).isSelected());
            next(panel); assertEquals(700, c.profile.stableMs()); assertTrue(c.profile.fadeViewed()); panel.dispose();
        });
    }
    @Test void pauseAndCameraStopDoNotCloseTheRecording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            next(panel); next(panel); next(panel); next(panel);
            button(panel, "processing").doClick(); assertTrue(c.recording); assertTrue(c.paused);
            button(panel, "processing").doClick(); assertFalse(c.paused);
            c.input = GazeResearchPanel.Input.WEBCAM; c.tracking = true; panel.refresh();
            button(panel, "stop-camera").doClick(); assertTrue(c.recording); assertTrue(c.paused); assertEquals(1, c.stopCameraCalls);
            next(panel); assertFalse(c.recording); panel.dispose();
        });
    }
    @Test void longScenarioInstructionsRemainReadableInNarrowWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new GazeResearchPanel(new ResearchPanelTestController()); next(panel);
            combo(panel, "scenario").setSelectedItem(GazeScenario.WALKTHROUGH);
            GazeResearchPanelPreview.prepareLayout(panel, 380, 850);
            JTextArea instructions = component(panel, "scenario-help", JTextArea.class);
            JScrollPane pane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, instructions);
            assertEquals(pane.getViewport().getExtentSize().width, pane.getViewport().getViewSize().width);
            assertTrue(instructions.getHeight() >= instructions.getPreferredSize().height);
            panel.dispose();
        });
    }
    @Test void navigationStaysWithInstructionsAndNeverBecomesTheConnectButton() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new GazeResearchPanel(new ResearchPanelTestController());
            GazeResearchPanelPreview.prepareLayout(panel, 380, 850);
            var next = button(panel, "next");
            Point position = SwingUtilities.convertPoint(next, 0, 0, panel);
            assertTrue(position.y < 240, "Navigation must stay beside the current step instructions");
            combo(panel, "input").setSelectedItem(GazeResearchPanel.Input.WEBCAM);
            assertEquals("Next: scenario", next.getText()); assertFalse(next.isEnabled());
            assertTrue(button(panel, "connect-webcam").isVisible());
            combo(panel, "input").setSelectedItem(GazeResearchPanel.Input.MOUSE);
            assertFalse(button(panel, "connect-webcam").isVisible()); assertTrue(next.isEnabled());
            panel.dispose();
        });
    }
    @Test void nativeAiHandoffIsExplicitAndAvailableDuringRecording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            next(panel); combo(panel, "scenario").setSelectedItem(GazeScenario.RELATIONSHIPS);
            panel.refresh(); assertTrue(c.actions.isEmpty());
            next(panel); next(panel); next(panel);
            assertEquals(RECORDING, panel.currentStep()); assertTrue(c.actions.isEmpty());
            button(panel, "ask-ai").doClick();
            assertEquals(List.of(GazeResearchPanel.Action.ASK_AI), c.actions); panel.dispose();
        });
    }
    @Test void contextStatusAndInspectionAreAvailableWithoutStoppingRecording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new ResearchPanelTestController(); var panel = new GazeResearchPanel(c);
            next(panel); combo(panel, "scenario").setSelectedItem(GazeScenario.RELATIONSHIPS);
            next(panel); next(panel); next(panel);
            c.contextStatus = "Preparing AI context: 65% · caller"; panel.refresh();
            assertTrue(component(panel, "context-status", JTextArea.class).getText().contains("65%"));
            c.contextStatus = "AI context ready: caller [WEBCAM]. Nothing sent."; panel.refresh();
            assertTrue(component(panel, "context-status", JTextArea.class).getText().contains("ready"));
            button(panel, "live-context").doClick();
            assertTrue(c.recording); assertEquals(RECORDING, panel.currentStep());
            assertEquals(List.of(GazeResearchPanel.Action.CONTEXT), c.actions);
            next(panel); button(panel, "context").doClick();
            assertEquals(RESULTS, panel.currentStep()); panel.dispose();
        });
    }
    static void next(GazeResearchPanel panel) { button(panel, "next").doClick(); }
    static JButton button(Container root, String name) { return component(root, name, JButton.class); }
    static JComboBox<?> combo(Container root, String name) { return component(root, name, JComboBox.class); }
    static <T extends Component> T component(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container container) {
                T match = find(container, name, type); if (match != null) return match;
            }
        }
        throw new AssertionError("Missing component: " + name);
    }
    private static <T extends Component> T find(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container container) { T found = find(container, name, type); if (found != null) return found; }
        }
        return null;
    }
}
