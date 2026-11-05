package io.github.gazehighlighter;

import com.intellij.openapi.options.Configurable;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;

public final class GazeFeatureConfigurable implements Configurable {
    private JCheckBox focus, fade, relations, history, explain;
    private JSpinner stable, fadeAfter, explainAfter;
    @Override public String getDisplayName() { return "Implicit Gaze Features"; }
    @Override public @Nullable JComponent createComponent() {
        focus = new JCheckBox("Keep the viewed block prominent; dim surrounding code");
        fade = new JCheckBox("De-emphasize previously viewed blocks; restore on looking back");
        relations = new JCheckBox("Highlight matching identifiers within the viewed block");
        history = new JCheckBox("Include recently viewed blocks in AI questions");
        explain = new JCheckBox("Prepare captured code for a native AI question");
        stable = new JSpinner(new SpinnerNumberModel(500, 200, 2000, 100));
        fadeAfter = new JSpinner(new SpinnerNumberModel(1500, 500, 30000, 500));
        explainAfter = new JSpinner(new SpinnerNumberModel(3000, 1000, 15000, 500));
        JPanel panel = FormBuilder.createFormBuilder()
                .addComponent(new JLabel("<html>Advanced combinations. For guided setup, open Tools → Implicit Gaze → Open Gaze Research.<br>Choose the AI question purpose in the Scenario step there.</html>"))
                .addComponent(focus).addComponent(fade)
                .addComponent(relations).addComponent(history).addComponent(explain)
                .addLabeledComponent("Stable target delay (ms):", stable)
                .addLabeledComponent("Viewed exposure before fading (ms):", fadeAfter)
                .addLabeledComponent("AI block viewing (ms):", explainAfter)
                .addComponent(new JLabel("<html>Gaze nominates context; exposure does not establish understanding.<br>"
                        + "Identifier highlights are lexical matches, not resolved references.<br>"
                        + "History adds up to three source snapshots to a local draft. Send explicitly in JetBrains AI Chat.<br>"
                        + "Use Tools → Implicit Gaze → Pause gaze effects to reveal all code immediately.</html>"))
                .addComponentFillVertically(new JPanel(), 0).getPanel();
        reset(); return panel;
    }
    @Override public boolean isModified() {
        var s = GazeFeatureSettings.getInstance().getState();
        return focus.isSelected() != s.focus || fade.isSelected() != s.fadeViewed
                || relations.isSelected() != s.relationships || history.isSelected() != s.historyContext
                || explain.isSelected() != s.explanations || value(stable) != s.stableMs
                || value(fadeAfter) != s.fadeAfterMs || value(explainAfter) != s.explainAfterMs;
    }
    @Override public void apply() {
        if (GazeRecordingService.isAnyRecording() || GazeCalibrationDialog.isActive() || GazePersonalizationDialog.isActive()) {
            GazeNotify.warn(null, "Stop recording and finish calibration or personalization before changing feature settings."); reset(); return;
        }
        var s = GazeFeatureSettings.getInstance().getState();
        s.focus = focus.isSelected(); s.fadeViewed = fade.isSelected(); s.relationships = relations.isSelected();
        s.historyContext = history.isSelected(); s.explanations = explain.isSelected();
        s.stableMs = value(stable); s.fadeAfterMs = value(fadeAfter); s.explainAfterMs = value(explainAfter);
        GazeDispatcher.suspendAllProjects(true);
        GazeRecordingService.configurationChangedForOpenProjects();
    }
    @Override public void reset() {
        var s = GazeFeatureSettings.getInstance().getState();
        focus.setSelected(s.focus); fade.setSelected(s.fadeViewed); relations.setSelected(s.relationships);
        history.setSelected(s.historyContext); explain.setSelected(s.explanations);
        stable.setValue(s.stableMs); fadeAfter.setValue(s.fadeAfterMs); explainAfter.setValue(s.explainAfterMs);
    }
    private static int value(JSpinner spinner) { return ((Number) spinner.getValue()).intValue(); }
}
