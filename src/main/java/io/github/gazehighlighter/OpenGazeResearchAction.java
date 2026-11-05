package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public final class OpenGazeResearchAction extends AnAction implements DumbAware {
    public static void open(Project project) {
        if (project == null || project.isDisposed()) return;
        var window = ToolWindowManager.getInstance(project).getToolWindow(GazeResearchToolWindowFactory.ID);
        if (window != null) window.show(() -> {
            for (var content : window.getContentManager().getContents())
                if (content.getComponent() instanceof GazeResearchPanel) { window.getContentManager().setSelectedContent(content); break; }
        });
    }
    @Override public void actionPerformed(@NotNull AnActionEvent e) { open(e.getProject()); }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabledAndVisible(e.getProject() != null); }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
