package io.github.gazehighlighter;

import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Modal, explicitly saved response profiling. Setup exercise, separate from a review recording. */
final class GazePersonalizationDialog extends DialogWrapper {
    private static boolean active;
    static boolean isActive() { return active; }
    static boolean open(Project project) {
        var input = GazeInputSettings.getInstance();
        if (active || GazeCalibrationDialog.isActive() || GazeRecordingService.isAnyRecording()) return false;
        if (input.getInputMode() != GazeInputMode.WEBCAM || input.isMock() || !input.isCalibrated()
                || !UniteyeGazeService.getInstance().isTrackingValid()) {
            GazeNotify.warn(project, "Personalization needs a connected, calibrated webcam. Mouse input cannot create a personal gaze profile."); return false;
        }
        active = true;
        GazePersonalizationDialog dialog = null;
        try { dialog = new GazePersonalizationDialog(project); return dialog.showAndGet(); }
        finally {
            if (dialog != null) dialog.cleanup();
            active = false;
        }
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private final JTextArea instructions = GazeResearchPanel.help("Follow the ring with your eyes as it moves between code rows. Six targets choose a response preset; four fresh targets check that fixed choice. About 25 seconds, with retries for missing samples.\n\nThis adjusts stability versus response delay, not calibration or AI timing. Keep the window still on the primary display. No code knowledge or target clicking needed. Raw samples are discarded after the task.", 5);
    private final TargetPanel targets = new TargetPanel();
    private final Timer timer = new Timer(30, e -> tick());
    private final int rowHeight;
    private final String calibrationKey;
    private final List<GazePersonalizationRun.Sample> samples = new ArrayList<>();
    private GazePersonalizationRun run;
    private GazePersonalizationRun.Result result;
    private Point origin;
    private long started, lastSample = -1;
    private double targetY;
    private int retries;
    private boolean collecting, failed;
    private GazePersonalizationDialog(Project project) {
        super(project);
        int height = 22;
        for (var editor : EditorFactory.getInstance().getAllEditors())
            if (!editor.isDisposed() && editor.getContentComponent().isShowing()) { height = editor.getLineHeight(); break; }
        rowHeight = Math.max(12, height);
        calibrationKey = GazeInputSettings.getInstance().calibrationKey();
        setTitle("Personalization — response profile"); setOKButtonText("Begin target task"); setResizable(false); init();
        var screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        var size = getWindow().getSize();
        getWindow().setLocation(screen.x + Math.max(0, (screen.width-size.width)/2), screen.y + Math.max(0, (screen.height-size.height)/2));
    }
    @Override protected JComponent createCenterPanel() {
        JPanel body = new JPanel(new BorderLayout(0, 10)); body.add(instructions, BorderLayout.NORTH);
        targets.setPreferredSize(new Dimension(690, 50 + 10 * rowHeight)); body.add(targets, BorderLayout.CENTER); return body;
    }
    @Override protected void doOKAction() {
        if (result != null) {
            var input = GazeInputSettings.getInstance();
            if (input.getInputMode() != GazeInputMode.WEBCAM || input.isMock() || !input.isCalibrated() || !calibrationKey.equals(input.calibrationKey())) {
                fail("Input or calibration changed. Restart the task; previous profile retained."); return;
            }
            input.savePersonalResponse(GazePersonalResponseProfile.create(result, calibrationKey, rowHeight));
            UniteyeGazeService.getInstance().resetFilter();
            super.doOKAction(); return;
        }
        run = new GazePersonalizationRun(rowHeight); failed = false; retries = 0;
        // Make the instruction area match its in-task height before measuring screen targets.
        // Otherwise shortening the introduction can move the target panel after sampling starts.
        instructions.setText("Follow the ring with your eyes. Blink normally. Cancel keeps your previous profile.");
        instructions.getParent().invalidate(); getWindow().validate();
        var screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        if (!screen.contains(new Rectangle(targets.getLocationOnScreen(), targets.getSize()))) {
            fail("The target area must fit on the primary display. Reduce editor text size or use a larger primary display, then reopen Personalization. Previous profile retained."); return;
        }
        beginTrial(); setOKActionEnabled(false);
        UniteyeGazeService.getInstance().setRawSampleListener(raw -> {
            long at = now();
            if (!collecting || at <= lastSample || at - started >= GazePersonalizationRun.TARGET_MS) return;
            var calibration = GazeInputSettings.getInstance().getCalibration();
            samples.add(new GazePersonalizationRun.Sample(calibration.x(raw.x, raw.y), calibration.y(raw.x, raw.y), at));
            lastSample = at;
        });
        timer.start(); tick();
    }
    private void beginTrial() {
        origin = targets.getLocationOnScreen(); targetY = origin.y + targets.centerY(run.row());
        samples.clear(); lastSample = -1; started = now(); collecting = true; targets.repaint();
    }
    private void tick() {
        if (!collecting) return;
        var input = GazeInputSettings.getInstance();
        if (!UniteyeGazeService.getInstance().isRunning() || input.isMock() || !input.isCalibrated()
                || !calibrationKey.equals(input.calibrationKey())) { fail("Tracking or calibration changed. Previous profile retained."); return; }
        if (!targets.getLocationOnScreen().equals(origin)) { fail("The window moved. Retry with the window still; previous profile retained."); return; }
        instructions.setText((run.checking() ? "Fresh-target check " : "Choosing a response: target ") + (run.index()+1)
                + (run.checking() ? " / 4" : " / 6") + ". Follow the ring. Blink normally. Cancel keeps your previous profile."
                + (retries > 0 ? "\nRetrying this target: too few valid samples. Check lighting and face framing." : ""));
        targets.repaint();
        if (now() - started < GazePersonalizationRun.TARGET_MS) return;
        collecting = false;
        boolean accepted = run.addTrial(new GazePersonalizationRun.Trial(run.row(), targetY, started, samples));
        if (!accepted) {
            if (++retries > 2) { fail("Not enough valid samples. Check camera framing and lighting, then retry. Previous profile retained."); return; }
        } else retries = 0;
        if (!run.complete()) { beginTrial(); return; }
        cleanup(); result = run.result(); setOKActionEnabled(true); setOKButtonText("Save & use profile");
        var profile = GazePersonalResponseProfile.create(result, calibrationKey, rowHeight);
        instructions.setText(profile.summary().replace("Personal profile:", "Suggested response:").replace("Saved ", "Measured ") + "\n\nStandard Steady on the same fresh targets: "
                + String.format(java.util.Locale.ROOT, "%.0f%% target-row samples; %.0f ms guided-task acquisition.", 100*result.standard().hitRate(), result.standard().acquisitionMs())
                + "\nAcquisition includes your reaction and tracker delay. These task results do not establish reading or fixation accuracy."
                + (result.validation().p90VerticalPx() > rowHeight/2.0 ? "\nVertical error exceeds half a task row. This profile cannot fix position bias. Recheck calibration, camera setup or use larger editor text." : "")
                + "\nSave replaces the one IDE-user profile and makes it available to all scenarios. Cancel retains the previous profile.");
        targets.repaint();
    }
    private void fail(String message) {
        cleanup(); failed = true; result = null; instructions.setText(message); setOKActionEnabled(true); setOKButtonText("Retry task"); targets.repaint();
    }
    private void cleanup() { timer.stop(); collecting = false; UniteyeGazeService.getInstance().setRawSampleListener(null); }
    private final class TargetPanel extends JComponent {
        private final String[] code = {"String createBadge(String name) {", "    if (isValidName(name)) {", "        return name.trim();", "    }", "    return \"Guest\";", "}", "boolean isValidName(String name) {", "    return name != null", "        && !name.isEmpty();", "}"};
        int centerY(int row) { return 25 + row*rowHeight + rowHeight/2; }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(35, 38, 43)); g.fillRect(0, 0, getWidth(), getHeight());
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, rowHeight-5)));
                g.setColor(new Color(180, 190, 205));
                for (int i=0; i<code.length; i++) g.drawString(String.format("%2d  %s", i+1, code[i]), 24, centerY(i) + g.getFontMetrics().getAscent()/2-2);
                if (collecting && !failed) {
                    int x = (int) (getWidth() * (run.index()%2 == 0 ? .25 : .65)), y = centerY(run.row());
                    g.setColor(new Color(95, 185, 255)); g.setStroke(new BasicStroke(2));
                    g.drawOval(x-8, y-8, 16, 16); g.fillOval(x-2, y-2, 4, 4);
                }
            } finally { g.dispose(); }
        }
    }
}
