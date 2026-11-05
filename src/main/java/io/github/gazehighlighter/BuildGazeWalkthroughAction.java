package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.*;
import org.jetbrains.annotations.NotNull;

public final class BuildGazeWalkthroughAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject(); if (project == null) return;
        GazeSession session = GazeRecordingService.getInstance(project).finishedSession();
        if (session == null || session.events.isEmpty()) { GazeNotify.warn(project, "Record a viewing session first."); return; }
        new GazeWalkthroughDialog(project, session).show();
    }
    @Override public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
