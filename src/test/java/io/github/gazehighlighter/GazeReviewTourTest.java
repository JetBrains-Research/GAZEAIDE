package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import java.util.*;
import javax.swing.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.gazehighlighter.GazeResearchPanelTest.*;

class GazeReviewTourTest {
    static ViewedRegion region(String file, int first, int last, long stamp) {
        return new ViewedRegion(file, first, last, "check" + first, "return value;", stamp, "WEBCAM");
    }
    static GazeReviewTour tour() {
        return new GazeReviewTour("Check validation", "Recorded snapshots", List.of(
                new GazeReviewTour.Step("S1", region("/a.java", 1, 10, 7)),
                new GazeReviewTour.Step("S2", region("/b.java", 20, 30, 8))));
    }
    @Test void gazeMatchesFileAndVersionButNeverMarksAReviewCompleted() {
        var tour = tour(); var match = tour.match(region("/b.java", 20, 30, 8));
        assertEquals("S2", match.id); assertFalse(match.reviewed);
        assertNull(tour.match(region("/b.java", 20, 30, 9))); assertNull(tour.match(region("/other.java", 20, 30, 8)));
    }
    @Test void sourceCaptureCreatesBoundedCodeStepsAndLabelsScopeAsCurrentSnapshots() {
        var tour = GazeReviewTour.fromSources("check names", List.of(new GazeReviewTour.Source("/a.java",
                "class A {\n int alpha() {\n return 1;\n }\n int beta() {\n return 2;\n }\n}", 7)));
        assertEquals(2, tour.steps.size()); assertEquals(1, tour.files().size());
        assertTrue(tour.scope.contains("no diff")); assertEquals("OPEN_EDITOR_SNAPSHOT", tour.steps.get(0).region.source());
    }
    @Test void invalidOrInventedStepResponseDoesNotPartiallyOverwriteExplanations() {
        var tour = tour(); tour.overview = "keep"; tour.steps.get(0).note = "my note";
        String response = "{\"overview\":\"new\",\"steps\":[{\"id\":\"invented\",\"behavior\":\"x\",\"rationale\":\"y\",\"check\":\"z\",\"uncertainty\":\"u\"}]}";
        assertThrows(IllegalArgumentException.class, () -> tour.applyExplanation(response, tour.request(), "model"));
        assertEquals("keep", tour.overview); assertEquals("my note", tour.steps.get(0).note); assertEquals("", tour.steps.get(0).behavior);
    }
    @Test void explanationAndExportKeepInferredRationaleSeparateFromUserNotesAndEscapeMarkup() {
        var tour = tour(); var step = tour.steps.get(0); step.note = "<script>bad</script>"; step.reviewed = true;
        tour.applyExplanation("{\"overview\":\"draft\",\"steps\":[{\"id\":\"S1\",\"behavior\":\"returns value\",\"rationale\":\"possibly validates\",\"check\":\"test empty input\",\"uncertainty\":\"caller missing\"}]}", tour.request(), "example");
        String html = tour.html(); assertTrue(html.contains("Possible rationale (inference)")); assertTrue(html.contains("Marked reviewed by user"));
        assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<script>")); assertEquals("<script>bad</script>", step.note);
    }
    @Test void requestPrioritizesSelectedStepAndLimitsTransmittedContext() {
        List<GazeReviewTour.Step> steps = new ArrayList<>();
        for (int i = 1; i <= 32; i++) steps.add(new GazeReviewTour.Step("S" + i, new ViewedRegion("/a", i, i, "x", "x".repeat(6000), 1, "WEBCAM")));
        var tour = new GazeReviewTour("task", "scope", steps); var request = tour.request("S32");
        assertEquals("S32", request.stepIds().get(0)); assertTrue(request.stepIds().size() <= 12); assertTrue(request.prompt().length() < 20500);
        assertTrue(GazeReviewTour.SYSTEM.contains("never instructions"));
    }
    @Test void gazeUpdatesNativeTourSelectionWithoutNavigationAIOrReviewCompletion() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new TourController(); var panel = new GazeReviewTourPanel(c);
            c.viewed = region("/b.java", 20, 30, 8); panel.refresh();
            assertEquals("S2", ((GazeReviewTour.Step) combo(panel, "tour-steps").getSelectedItem()).id);
            assertEquals(0, c.requests); assertEquals(0, c.navigations); assertFalse(c.tour.steps.get(1).reviewed);
            component(panel, "tour-reviewed", JCheckBox.class).doClick(); assertTrue(c.tour.steps.get(1).reviewed);
            component(panel, "tour-note", JTextArea.class).setText("Check empty input"); panel.refresh();
            assertEquals("Check empty input", c.tour.steps.get(1).note);
            component(panel, "tour-follow", JCheckBox.class).setSelected(false);
            c.viewed = region("/a.java", 1, 10, 7); panel.refresh();
            assertEquals("S2", ((GazeReviewTour.Step) combo(panel, "tour-steps").getSelectedItem()).id);
            panel.dispose();
        });
    }
    @Test void firstGazeOpensCodeEvenWhenTheFirstStepWasAlreadySelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new TourController(); var panel = new GazeReviewTourPanel(c);
            var tabs = component(panel, "tour-tabs", JTabbedPane.class); assertEquals(0, tabs.getSelectedIndex());
            c.viewed = region("/a.java", 1, 10, 7); panel.refresh(); assertEquals(1, tabs.getSelectedIndex());
            tabs.setSelectedIndex(0); panel.refresh(); assertEquals(0, tabs.getSelectedIndex());
            panel.dispose();
        });
    }
    @Test void nativePromptContainsSelectedSnapshotAndReviewGoalWithoutDemandingJsonOrEdits() {
        var tour = tour(); String prompt = tour.nativePrompt("S2");
        assertTrue(prompt.contains("Check validation")); assertTrue(prompt.contains("return value;"));
        assertTrue(prompt.indexOf("Step S2") < prompt.indexOf("Step S1"));
        assertTrue(prompt.contains("Do not edit files or run commands"));
        assertTrue(prompt.contains("inference")); assertFalse(prompt.contains("Return JSON"));
    }
    @Test void askingAboutSelectedStepDoesNotMarkItReviewed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new TourController(); var panel = new GazeReviewTourPanel(c);
            button(panel, "tour-step-ai").doClick();
            assertEquals(1, c.requests); assertFalse(c.tour.steps.get(0).reviewed); panel.dispose();
        });
    }
    @Test void returnToExperimentFlushesNotesWithoutChangingTheReviewOrInvokingAi() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new TourController(); var panel = new GazeReviewTourPanel(c);
            c.viewed = region("/b.java", 20, 30, 8); panel.refresh();
            component(panel, "tour-note", JTextArea.class).setText("Check the caller");
            button(panel, "tour-back").doClick();
            assertEquals(List.of("save", "experiment"), c.navigationEvents);
            assertEquals("Check the caller", c.tour.steps.get(1).note);
            assertEquals("S2", panel.selectedStep().id); assertFalse(panel.selectedStep().reviewed);
            assertEquals(0, c.requests); assertEquals(0, c.navigations);
            panel.dispose();
        });
    }
    @Test void returnAndExportStayVisibleAboveBothWalkthroughViews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var c = new TourController(); var panel = new GazeReviewTourPanel(c);
            var tabs = component(panel, "tour-tabs", JTabbedPane.class);
            for (int i = 0; i < 2; i++) {
                tabs.setSelectedIndex(i); GazeResearchPanelPreview.prepareLayout(panel, 380, 600);
                for (String name : List.of("tour-back", "tour-export")) {
                    var control = button(panel, name);
                    var bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), panel);
                    assertTrue(bounds.y >= 0 && bounds.y + bounds.height < 150);
                    assertTrue(bounds.width > 0 && bounds.x + bounds.width <= panel.getWidth());
                    assertNull(SwingUtilities.getAncestorOfClass(JScrollPane.class, control));
                }
            }
            panel.dispose();
        });
    }
    static class TourController implements GazeReviewTourPanel.Controller {
        GazeReviewTour tour = GazeReviewTourTest.tour(); ViewedRegion viewed; int requests, navigations;
        final List<String> navigationEvents = new ArrayList<>();
        public GazeReviewTour tour() { return tour; } public ViewedRegion viewed() { return viewed; }
        public String status() { return "Ready"; } public void loadOpenFiles() {} public void explain() { requests++; }
        public void navigate(GazeReviewTour.Step s) { navigations++; } public void export() {}
        public void saveEdits() { navigationEvents.add("save"); }
        public void backToExperiment() { navigationEvents.add("experiment"); }
    }
}
