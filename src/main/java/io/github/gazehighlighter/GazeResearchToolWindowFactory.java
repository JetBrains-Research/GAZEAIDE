package io.github.gazehighlighter;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class GazeResearchToolWindowFactory implements ToolWindowFactory, DumbAware {
    public static final String ID = "Gaze Research";
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        GazeResearchPanel panel = new GazeResearchPanel(new GazeResearchController(project));
        var content = ContentFactory.getInstance().createContent(panel, "Experiment", false);
        content.setDisposer(panel::dispose);
        toolWindow.getContentManager().addContent(content);
        panel.startRefreshing();
    }
}
