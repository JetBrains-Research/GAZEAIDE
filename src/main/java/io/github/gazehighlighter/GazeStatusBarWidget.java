package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Status-bar widget showing:
 *   ● Implicit: Reading      (green dot)
 *   ● Implicit: Explaining   (blue bold)
 *   ● REC                    (red, appended while a gaze recording session is active)
 *
 * Left-click opens Gaze Research; right-click opens the quick actions also registered
 * under Tools → Implicit Gaze.
 */
public class GazeStatusBarWidget implements CustomStatusBarWidget {

    public static final String ID = "Implicit";

    private static final String GREEN_HEX = "#32C864";
    private static final String ACCENT_HEX = "#5AAFFF";
    private static final String REC_HEX = "#E5484D";

    private final Project project;
    private final JLabel  label;
    private final Timer statusTimer = new Timer(1000, e -> repaint());

    private GazeMode currentMode = GazeMode.READING;

    public GazeStatusBarWidget(Project project) {
        this.project = project;

        label = new JLabel();
        label.setOpaque(false);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        label.setToolTipText("Open Gaze Research: setup, scenarios, recording and results. Right-click for quick actions.");

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) showPopup();
                else OpenGazeResearchAction.open(project);
            }
            @Override
            public void mouseEntered(MouseEvent e) {
                label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
            @Override
            public void mouseExited(MouseEvent e) {
                label.setCursor(Cursor.getDefaultCursor());
            }
        });

        refresh(GazeMode.READING);
    }

    void refresh(GazeMode mode) {
        this.currentMode = mode;
        SwingUtilities.invokeLater(this::repaint);
    }

    /** Called by {@link GazeRecordingService} (via its on-change listener) when recording starts/stops. */
    void refreshRecording() {
        SwingUtilities.invokeLater(this::repaint);
    }

    private void repaint() {
        if (project.isDisposed()) return;
        boolean paused = GazeFeatureSettings.getInstance().getState().paused;
        GazeInputSettings input = GazeInputSettings.getInstance();
        String source = input.getInputMode() == GazeInputMode.MOUSE ? "Mouse proxy"
                : input.isMock() ? "Mouse simulation" : "Webcam";
        boolean lost = input.getInputMode() == GazeInputMode.WEBCAM
                && !UniteyeGazeService.getInstance().isTrackingValid();
        String modeHtml = paused ? "Paused" : lost ? "Waiting for gaze" : currentMode == GazeMode.EXPLAINING
                ? "<b><font color='" + ACCENT_HEX + "'>" + currentMode.label + "</font></b>"
                : currentMode.label;
        boolean recording = GazeRecordingService.getInstance(project).isRecording();
        String recHtml = recording
                ? "&nbsp;&nbsp;<font color='" + REC_HEX + "'>&#9679;&nbsp;<b>REC</b></font>" : "";
        label.setText("<html><font color='" + GREEN_HEX + "'>&#9679;</font>"
                + "&nbsp;<b>Implicit:</b>&nbsp;" + source + " · " + modeHtml + recHtml + "</html>");
    }

    private void showPopup() {
        ActionManager am = ActionManager.getInstance();
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(am.getAction("io.github.gazehighlighter.OpenResearch"));
        group.addSeparator();
        group.add(am.getAction("io.github.gazehighlighter.PauseEffects"));
        group.add(am.getAction("io.github.gazehighlighter.ShowContext"));
        group.addSeparator();
        group.add(am.getAction("io.github.gazehighlighter.StartRecording"));
        group.add(am.getAction("io.github.gazehighlighter.StopRecording"));
        group.addSeparator();
        group.add(am.getAction("io.github.gazehighlighter.ExportSession"));
        group.add(am.getAction("io.github.gazehighlighter.ReadReport"));
        group.add(am.getAction("io.github.gazehighlighter.BuildWalkthrough"));
        group.addSeparator();
        group.add(new AnAction("Current File Coverage…") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                GazeModeService svc = GazeModeService.getInstance(project);
                if (svc != null) svc.showSummary();
            }
        });

        am.createActionPopupMenu(ActionPlaces.STATUS_BAR_PLACE, group)
                .getComponent().show(label, 0, -label.getHeight());
    }

    // ── StatusBarWidget ───────────────────────────────────────────────────────

    @Override public @NotNull String ID() { return ID; }
    @Override public @Nullable WidgetPresentation getPresentation() { return null; }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        GazeModeService svc = GazeModeService.getInstance(project);
        if (svc != null) svc.registerWidget(this);
        GazeRecordingService.getInstance(project).setOnChangeListener(this::refreshRecording);
        statusTimer.start();
    }

    @Override
    public void dispose() {
        statusTimer.stop();
        GazeModeService svc = GazeModeService.getInstance(project);
        if (svc != null) svc.registerWidget(null);
        GazeRecordingService.getInstance(project).setOnChangeListener(null);
    }

    @Override public @NotNull JComponent getComponent() { return label; }
}
