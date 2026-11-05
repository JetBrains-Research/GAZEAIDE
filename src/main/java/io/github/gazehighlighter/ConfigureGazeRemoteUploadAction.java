package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class ConfigureGazeRemoteUploadAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject(); if (project == null) return;
        if (GazeRecordingService.getInstance(project).isRecording()) {
            GazeNotify.warn(project, "Stop recording before configuring remote upload."); return;
        }
        new GazeRemoteUploadDialog(project).show();
    }
    @Override public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabledAndVisible(event.getProject() != null); }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
