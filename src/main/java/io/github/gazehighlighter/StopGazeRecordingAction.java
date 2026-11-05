package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Stops the current gaze recording session; the captured events stay available for export. */
public class StopGazeRecordingAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        GazeSession session = GazeRecordingService.getInstance(project).stop();
        new GazeResearchController(project).setPaused(true);
        GazeNotify.info(project, "Gaze recording stopped — " + session.events.size()
                + " line visits captured. Open Gaze Research for saved results and export.");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean recording = project != null && GazeRecordingService.getInstance(project).isRecording();
        e.getPresentation().setEnabledAndVisible(recording);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
