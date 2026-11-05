package io.github.gazehighlighter;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Renders the real Swing panel with synthetic state; does not launch IntelliJ or a camera. */
public final class GazeResearchPanelPreview {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        UIManager.put("control", new Color(43, 45, 48));
        UIManager.put("info", new Color(43, 45, 48));
        UIManager.put("nimbusBase", new Color(50, 55, 65));
        UIManager.put("nimbusBlueGrey", new Color(67, 70, 76));
        UIManager.put("nimbusLightBackground", new Color(36, 38, 41));
        UIManager.put("text", new Color(225, 227, 232));
        UIManager.put("nimbusFocus", new Color(108, 158, 255));
        Path directory = Path.of(args.length == 0 ? "build/ui-preview" : args[0]);
        Files.createDirectories(directory);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var controller = new ResearchPanelTestController();
                controller.saved.add(new GazeResearchPanel.SavedSession("old", "Nov 2 13:00 · P07 · validation"));
                var panel = new GazeResearchPanel(controller);
                render(panel, directory.resolve("input.png"), 380, 760);
                GazeResearchPanelTest.combo(panel, "input").setSelectedItem(GazeResearchPanel.Input.WEBCAM);
                render(panel, directory.resolve("input-webcam.png"), 380, 760);
                GazeResearchPanelTest.button(panel, "connect-webcam").doClick();
                controller.tracking = true; panel.refresh();
                render(panel, directory.resolve("input-webcam-ready.png"), 380, 760);
                GazeResearchPanelTest.combo(panel, "input").setSelectedItem(GazeResearchPanel.Input.MOUSE);
                GazeResearchPanelTest.next(panel);
                GazeResearchPanelTest.combo(panel, "scenario").setSelectedItem(GazeScenario.WALKTHROUGH);
                render(panel, directory.resolve("scenario.png"), 380, 760);
                GazeResearchPanelTest.combo(panel, "scenario").setSelectedItem(GazeScenario.COVERAGE);
                render(panel, directory.resolve("scenario-coverage.png"), 380, 760);
                GazeResearchPanelTest.combo(panel, "scenario").setSelectedItem(GazeScenario.RELATIONSHIPS);
                render(panel, directory.resolve("scenario-context.png"), 380, 760);
                render(panel, directory.resolve("scenario-context-tall.png"), 380, 1200);
                GazeResearchPanelTest.next(panel);
                GazeResearchPanelTest.component(panel, "participant", JTextField.class).setText("P07");
                GazeResearchPanelTest.component(panel, "task", JTextField.class).setText("Check whitespace validation");
                render(panel, directory.resolve("session.png"), 380, 760);
                GazeResearchPanelTest.next(panel);
                render(panel, directory.resolve("ready.png"), 380, 760);
                GazeResearchPanelTest.next(panel);
                controller.contextStatus = "Preparing AI context: 65% · createBadge. Keep reading within this block; moving between its lines is fine.";
                panel.refresh(); render(panel, directory.resolve("recording-context-waiting.png"), 380, 760);
                controller.contextStatus = "Context prepared locally: createBadge [MOUSE_PROXY]. Preview code or open a question below. No AI request sent by GAZEAIDE.";
                panel.refresh();
                controller.offer = "Compare viewed regions\nCaptured: createBadge [MOUSE_PROXY]"; panel.refresh();
                render(panel, directory.resolve("recording.png"), 380, 760);
                GazeResearchPanelTest.next(panel);
                render(panel, directory.resolve("results.png"), 380, 760);
                panel.dispose();
                String remoteSetup;
                try (var source = GazeResearchPanelPreview.class.getResourceAsStream("/remote/setup.txt")) {
                    remoteSetup = new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                var remotePanel = new GazeRemoteUploadPanel(GazeRemoteConnection.defaults(), remoteSetup, action -> {});
                remotePanel.showStatus("Remote upload off · local recording only\nNo storage is connected.", false);
                render(remotePanel, directory.resolve("remote-connection.png"), 690, 650);
                GazeResearchPanelTest.component(remotePanel, "remote-connector", JComboBox.class).setSelectedItem(GazeRemoteConnection.Connector.GOOGLE_DRIVE);
                render(remotePanel, directory.resolve("remote-google-drive.png"), 690, 650);
                var personalController = new ResearchPanelTestController(); personalController.input = GazeResearchPanel.Input.WEBCAM;
                personalController.running = personalController.tracking = personalController.calibrated = true;
                var personalPanel = new GazeResearchPanel(personalController);
                GazeResearchPanelTest.next(personalPanel);
                GazeResearchPanelTest.combo(personalPanel, "scenario").setSelectedItem(GazeScenario.PERSONALIZATION);
                render(personalPanel, directory.resolve("scenario-personalization.png"), 380, 900);
                GazeResearchPanelTest.next(personalPanel); GazeResearchPanelTest.next(personalPanel); GazeResearchPanelTest.next(personalPanel);
                render(personalPanel, directory.resolve("personalization-complete.png"), 380, 760);
                GazeResearchPanelTest.next(personalPanel); GazeResearchPanelTest.combo(personalPanel, "scenario").setSelectedItem(GazeScenario.COVERAGE);
                GazeResearchPanelTest.next(personalPanel); render(personalPanel, directory.resolve("session-personalized.png"), 380, 900);
                GazeResearchPanelTest.next(personalPanel); GazeResearchPanelTest.next(personalPanel);
                render(personalPanel, directory.resolve("recording-coverage.png"), 380, 760); personalPanel.dispose();
                var tourController = new GazeReviewTourTest.TourController();
                tourController.tour.overview = "Synthetic example: compare the caller's assumptions with validation behavior.";
                var tourPanel = new GazeReviewTourPanel(tourController);
                render(tourPanel, directory.resolve("tour-overview.png"), 380, 900);
                tourController.viewed = GazeReviewTourTest.region("/b.java", 20, 30, 8); tourPanel.refresh();
                render(tourPanel, directory.resolve("tour-code.png"), 380, 1100);
                tourPanel.dispose();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        System.out.println("Synthetic native-panel previews: " + directory.toAbsolutePath());
    }
    private static void render(JComponent panel, Path path, int width, int height) throws Exception {
        prepareLayout(panel, width, height);
        BufferedImage image = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.scale(2, 2);
        panel.printAll(graphics); graphics.dispose(); ImageIO.write(image, "png", path.toFile());
    }
    static void prepareLayout(JComponent panel, int width, int height) {
        panel.setSize(width, height);
        for (int i = 0; i < 5; i++) { invalidate(panel); layout(panel); }
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static void invalidate(Container container) {
        for (Component child : container.getComponents()) if (child instanceof Container nested) invalidate(nested);
        container.invalidate();
    }
}
