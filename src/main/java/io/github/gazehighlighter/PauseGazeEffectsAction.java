package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.*;
import org.jetbrains.annotations.NotNull;

public final class PauseGazeEffectsAction extends ToggleAction {
    @Override public boolean isSelected(@NotNull AnActionEvent e) { return GazeFeatureSettings.getInstance().getState().paused; }
    @Override public void setSelected(@NotNull AnActionEvent e, boolean paused) {
        GazeFeatureSettings.getInstance().getState().paused = paused;
        GazeDispatcher.suspendAllProjects(true);
        GazeRecordingService.configurationChangedForOpenProjects();
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
