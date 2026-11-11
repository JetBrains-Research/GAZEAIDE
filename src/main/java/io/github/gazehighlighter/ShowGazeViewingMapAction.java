package io.github.gazehighlighter;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/** Opens a dedicated local browser map; no server or network request is needed. */
public final class ShowGazeViewingMapAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject(); if (project == null) return;
        var session = GazeRecordingService.getInstance(project).finishedSession();
        if (session == null) {GazeNotify.warn(project, "Record or open a saved recording first."); return;}
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Path directory = Path.of(PathManager.getSystemPath(), "gazeaide", "viewing-maps");
                Files.createDirectories(directory);
                Path file = GazeViewingMap.export(session, Files.createTempFile(directory, "viewing-map-", ".html"));
                ApplicationManager.getApplication().invokeLater(() -> BrowserUtil.browse(file.toFile()));
            } catch (Exception ex) {GazeNotify.warn(project, "Could not open viewing map: " + ex.getMessage());}
        });
    }
    @Override public void update(@NotNull AnActionEvent e) {
        var project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null && GazeRecordingService.getInstance(project).hasData());
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() {return ActionUpdateThread.BGT;}
}
