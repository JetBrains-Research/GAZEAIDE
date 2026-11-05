package io.github.gazehighlighter;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/** Nine robust training targets followed by five independent accuracy targets. */
final class GazeCalibrationDialog {
    private static volatile GazeCalibrationDialog active;
    static boolean isActive() { return active != null; }
    static void cancelActive() { if (active != null) active.close(); }
    static void open() { open(false); }
    static void checkAccuracy() { open(true); }
    private static void open(boolean checkOnly) {
        if (active != null || GazePersonalizationDialog.isActive() || GazeRecordingService.isAnyRecording()) return;
        var settings = GazeInputSettings.getInstance();
        if (checkOnly && !settings.isCalibrated()) { GazeNotify.warn(null, "Calibrate first, then check accuracy."); return; }
        if (!UniteyeGazeService.getInstance().isTrackingValid()) { GazeNotify.warn(null, "Wait for valid webcam samples before calibrating."); return; }
        active = new GazeCalibrationDialog(checkOnly);
        active.start();
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private final boolean checkOnly;
    private final Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    // A real owned dialog can receive focus/Escape, including when launched from IDE Settings.
    private final JDialog window = new JDialog(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow());
    private final TargetPanel panel = new TargetPanel();
    private final JLabel hint = new JLabel("", SwingConstants.CENTER);
    private final JButton retry = new JButton("Retry this target");
    private final Timer timer = new Timer(30, e -> tick());
    private final GazeCalibrationRun run;
    private final int lineHeight;

    private GazeCalibrationDialog(boolean checkOnly) {
        this.checkOnly = checkOnly;
        run = new GazeCalibrationRun(bounds, checkOnly ? GazeInputSettings.getInstance().getCalibration() : null, now());
        int height = 20;
        for (Editor editor : EditorFactory.getInstance().getAllEditors())
            if (!editor.isDisposed() && editor.getContentComponent().isShowing()) { height = editor.getLineHeight(); break; }
        lineHeight = height;
        window.setUndecorated(true); window.setBounds(bounds); window.setAlwaysOnTop(true);
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { close(); }
        });
        panel.setLayout(new BorderLayout()); hint.setForeground(Color.LIGHT_GRAY);
        panel.setFocusable(true);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.CENTER)); top.setOpaque(false);
        JButton cancel = new JButton("Cancel calibration / check (Esc)"); cancel.addActionListener(e -> close());
        retry.setEnabled(false); retry.addActionListener(e -> { run.retryTarget(now()); tick(); });
        top.add(retry); top.add(cancel); panel.add(top, BorderLayout.NORTH);
        hint.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        panel.add(hint, BorderLayout.SOUTH); window.setContentPane(panel);
        panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        panel.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { close(); }
        });
    }
    private void start() {
        UniteyeGazeService.getInstance().setRawSampleListener(p -> run.sample(p.x, p.y, now()));
        window.setVisible(true); panel.requestFocusInWindow(); timer.start(); tick();
    }
    private void tick() {
        run.tick(now());
        if (run.phase() == GazeCalibrationRun.Phase.DONE || run.phase() == GazeCalibrationRun.Phase.FAILED) { finish(); return; }
        retry.setEnabled(run.phase() == GazeCalibrationRun.Phase.PAUSED);
        hint.setText("<html><div style='text-align:center'>" + run.message()
                + "<br>Follow the dot with your eyes. Blink normally. Esc cancels.<br>"
                + (run.checking() ? "Accuracy check — 5 new targets" : "Calibration — 9 targets, then an accuracy check") + "</div></html>");
        panel.repaint();
    }
    private void finish() {
        boolean success = run.phase() == GazeCalibrationRun.Phase.DONE;
        close();
        if (!success) { GazeNotify.warn(null, "Calibration/check failed. " + run.message() + " Previous calibration retained."); return; }
        var quality = run.validation();
        String guidance = quality.p90VerticalPx() > lineHeight / 2.0
                ? "\n\nError exceeds half a code-line height. Individual-line selection may be unreliable. Try larger editor text, better lighting and a stable camera/head position; check again. Smoothing reduces jitter, not position bias."
                : "\n\nThe measured error is below half a code-line height at this font size. Recheck after moving the camera or changing your seating/lighting.";
        Object[] choices = checkOnly ? new Object[]{"Keep result", "Check again", "Cancel"}
                : new Object[]{"Use calibration", "Retry calibration", "Cancel"};
        int choice = JOptionPane.showOptionDialog(null, quality.summary(lineHeight) + guidance,
                checkOnly ? "Webcam gaze accuracy" : "Review calibration accuracy", JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, choices, choices[0]);
        if (choice == 0) {
            var settings = GazeInputSettings.getInstance();
            if (!checkOnly) settings.setCalibration(run.calibration());
            settings.setCalibrationQuality(quality, bounds);
            UniteyeGazeService.getInstance().resetFilter();
            GazeRecordingService.configurationChangedForOpenProjects();
            GazeNotify.info(null, checkOnly ? "Accuracy check saved. Return to Gaze Research to continue." : "Calibration saved. Return to Gaze Research to continue.");
        } else if (choice == 1) open(checkOnly);
    }
    private void close() {
        if (active != this) return;
        timer.stop(); UniteyeGazeService.getInstance().setRawSampleListener(null);
        active = null; window.setVisible(false); window.dispose();
    }
    private final class TargetPanel extends JComponent {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(24, 27, 32)); g.fillRect(0, 0, getWidth(), getHeight());
                double[] target = run.target();
                int x = (int) Math.round(target[0] * getWidth()), y = (int) Math.round(target[1] * getHeight());
                g.setColor(run.phase() == GazeCalibrationRun.Phase.SETTLE ? new Color(90, 175, 255) : new Color(245, 180, 90));
                g.fillOval(x-10, y-10, 20, 20); g.setColor(Color.WHITE); g.fillOval(x-2, y-2, 4, 4);
                g.setStroke(new BasicStroke(3)); g.drawArc(x-23, y-23, 46, 46, 90, -(int) (360 * run.progress(now())));
                g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
                String label = (run.checking() ? "Check " : "Target ") + (run.index()+1) + " / " + run.count();
                g.drawString(label, x-g.getFontMetrics().stringWidth(label)/2, y-36);
            } finally { g.dispose(); }
        }
    }
}
