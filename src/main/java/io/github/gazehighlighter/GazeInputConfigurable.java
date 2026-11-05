package io.github.gazehighlighter;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings UI under <b>Settings → Tools → Implicit Gaze</b>.
 *
 * <p>Chooses the gaze input source (mouse proxy vs. the UnitEye webcam eye tracker) and
 * configures it. Edits are committed to {@link GazeInputSettings} only on {@link #apply()},
 * which also starts/stops {@link UniteyeGazeService} to match.
 */
public final class GazeInputConfigurable implements Configurable {

    private JPanel root;

    private JBRadioButton mouseRadio;
    private JBRadioButton webcamRadio;
    private JBCheckBox     mockCheck;
    private JButton        restartButton;
    private JButton        calibrateButton, accuracyButton;
    private JBCheckBox cursorCheck;
    private JBCheckBox personalCheck;
    private JBLabel personalSummary;
    private JComboBox<GazeResponse> response;
    private JBLabel        statusLabel;
    private JBLabel        calibrationLabel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Implicit Gaze";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mouseRadio  = new JBRadioButton("Mouse (dwell as a gaze proxy)");
        webcamRadio = new JBRadioButton("Webcam eye tracker (UnitEye)");
        ButtonGroup group = new ButtonGroup();
        group.add(mouseRadio);
        group.add(webcamRadio);

        mockCheck = new JBCheckBox("Verify without a camera (simulate screen gaze with the mouse)");

        restartButton = new JButton("Restart tracker");
        restartButton.addActionListener(e -> {
            if (GazeRecordingService.isAnyRecording() || GazeCalibrationDialog.isActive() || GazePersonalizationDialog.isActive()) return;
            // Persist current edits first so the tracker launches with the chosen options.
            apply();
            UniteyeGazeService.getInstance().restart();
        });

        calibrateButton = new JButton("Calibrate…");
        calibrateButton.addActionListener(e -> {
            if (GazeRecordingService.isAnyRecording() || GazeCalibrationDialog.isActive() || GazePersonalizationDialog.isActive()) return;
            apply();   // make sure the tracker is running with the current settings first
            if (!UniteyeGazeService.getInstance().isRunning()) {
                JOptionPane.showMessageDialog(root,
                        UniteyeGazeService.getInstance().getStatusMessage()
                                + "\nCalibrate after valid gaze samples arrive. See Gaze Research → Input for live connection status.",
                        "Implicit Gaze", JOptionPane.WARNING_MESSAGE);
                return;
            }
            GazeCalibrationDialog.open();
        });

        accuracyButton = new JButton("Check accuracy…");
        accuracyButton.addActionListener(e -> { if (!GazeRecordingService.isAnyRecording() && !GazeCalibrationDialog.isActive() && !GazePersonalizationDialog.isActive()) { apply(); GazeCalibrationDialog.checkAccuracy(); } });
        cursorCheck = new JBCheckBox("Show gaze cursor (blue gutter dot)");
        personalCheck = new JBCheckBox("Use my personal response profile in every scenario");
        personalSummary = new JBLabel();
        response = new JComboBox<>(GazeResponse.values());
        personalCheck.addActionListener(e -> response.setEnabled(webcamRadio.isSelected() && !personalCheck.isSelected() && !GazeRecordingService.isAnyRecording()));
        statusLabel      = new JBLabel();
        calibrationLabel = new JBLabel();
        calibrationLabel.setForeground(JBColor.GRAY);

        // Webcam-only fields toggle with the selected source.
        Runnable syncEnabled = () -> {
            boolean webcam = webcamRadio.isSelected();
            mockCheck.setEnabled(webcam);
            restartButton.setEnabled(webcam);
            calibrateButton.setEnabled(webcam && !mockCheck.isSelected());
            response.setEnabled(webcam && !personalCheck.isSelected() && !GazeRecordingService.isAnyRecording());
            personalCheck.setEnabled(webcam && GazeInputSettings.getInstance().hasPersonalResponse() && !GazeRecordingService.isAnyRecording());
            accuracyButton.setEnabled(webcam && !mockCheck.isSelected() && GazeInputSettings.getInstance().isCalibrated());
            refreshStatus();
        };
        mouseRadio.addActionListener(e -> syncEnabled.run());
        webcamRadio.addActionListener(e -> syncEnabled.run());
        mockCheck.addActionListener(e -> syncEnabled.run());

        JPanel restartRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        restartRow.add(restartButton, BorderLayout.WEST);
        restartRow.add(statusLabel, BorderLayout.CENTER);

        JPanel calibrateRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        calibrateRow.add(calibrateButton, BorderLayout.WEST);
        calibrateRow.add(calibrationLabel, BorderLayout.CENTER);

        root = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("Gaze input source:"))
                .addComponent(mouseRadio)
                .addComponent(webcamRadio)
                .addComponentToRightColumn(new JBLabel(
                        "Runs UnitEye's browser gaze pipeline (webcam → MediaPipe → EyeMU) in a "
                        + "hidden embedded browser, smoothed and bias-corrected with the calibration "
                        + "below. Needs camera permission and internet access on first use (downloads "
                        + "the ONNX/MediaPipe runtime)."))
                .addSeparator()
                .addComponent(mockCheck)
                .addComponent(restartRow)
                .addComponent(calibrateRow)
                .addComponent(accuracyButton)
                .addComponent(cursorCheck)
                .addComponent(personalCheck)
                .addComponent(personalSummary)
                .addLabeledComponent("Standard webcam response:", response)
                .addComponentToRightColumn(new JBLabel(
                        "Calibration uses 9 targets and checks accuracy at 5 new targets on the primary display. "
                        + "Recheck after moving the camera or changing seating/lighting. Steady reduces jitter "
                        + "with a short delay; it cannot correct position bias or guarantee line-level accuracy."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        reset();
        return root;
    }

    private void refreshStatus() {
        if (statusLabel == null) return;
        if (!webcamRadio.isSelected()) {
            statusLabel.setText("");
            calibrationLabel.setText("");
            return;
        }
        statusLabel.setText(UniteyeGazeService.getInstance().getStatusMessage());
        calibrationLabel.setText("<html><body style='width:320px'>" + GazeInputSettings.getInstance().calibrationSummary() + "</body></html>");
    }

    // ── Configurable contract ────────────────────────────────────────────────────

    @Override
    public boolean isModified() {
        GazeInputSettings s = GazeInputSettings.getInstance();
        return selectedMode() != s.getInputMode()
                || mockCheck.isSelected() != s.isMock()
                || cursorCheck.isSelected() != s.isShowCursor() || response.getSelectedItem() != s.getStandardResponse()
                || personalCheck.isSelected() != s.isPersonalResponseActive();
    }

    @Override
    public void apply() {
        GazeInputSettings s = GazeInputSettings.getInstance();
        if (GazeRecordingService.isAnyRecording() || GazeCalibrationDialog.isActive() || GazePersonalizationDialog.isActive()) {
            GazeNotify.warn(null, "Stop recording and finish calibration or personalization before applying input settings. Use the recording-page cursor switch to change visibility during recording.");
            reset(); return;
        }
        s.setShowCursor(cursorCheck.isSelected());
        s.setResponse((GazeResponse) response.getSelectedItem());
        s.setUsePersonalResponse(personalCheck.isSelected());
        UniteyeGazeService.getInstance().resetFilter();
        GazeInputMode mode = selectedMode();
        boolean restartRequired = mode == GazeInputMode.WEBCAM && s.isMock() != mockCheck.isSelected();
        GazeDispatcher.suspendAllProjects(true);
        s.setInputMode(mode);
        s.setMock(mockCheck.isSelected());

        // Start or stop the tracker to match the (now persisted) settings.
        if (restartRequired) UniteyeGazeService.getInstance().restart();
        else UniteyeGazeService.getInstance().applyMode(mode);
        GazeRecordingService.configurationChangedForOpenProjects();
        refreshStatus();
    }

    @Override
    public void reset() {
        GazeInputSettings s = GazeInputSettings.getInstance();
        boolean webcam = s.getInputMode() == GazeInputMode.WEBCAM;
        mouseRadio.setSelected(!webcam);
        webcamRadio.setSelected(webcam);
        mockCheck.setSelected(s.isMock());
        cursorCheck.setSelected(s.isShowCursor()); response.setSelectedItem(s.getStandardResponse()); personalCheck.setSelected(s.isPersonalResponseActive());
        personalSummary.setText("<html><body style='width:450px'>" + s.personalResponseSummary() + "</body></html>");
        response.setEnabled(webcam && !s.isPersonalResponseActive() && !GazeRecordingService.isAnyRecording());
        personalCheck.setEnabled(webcam && s.hasPersonalResponse() && !GazeRecordingService.isAnyRecording());
        accuracyButton.setEnabled(webcam && !s.isMock() && s.isCalibrated());

        mockCheck.setEnabled(webcam);
        restartButton.setEnabled(webcam);
        calibrateButton.setEnabled(webcam && !mockCheck.isSelected());
        refreshStatus();
    }

    private GazeInputMode selectedMode() {
        return webcamRadio.isSelected() ? GazeInputMode.WEBCAM : GazeInputMode.MOUSE;
    }
}
