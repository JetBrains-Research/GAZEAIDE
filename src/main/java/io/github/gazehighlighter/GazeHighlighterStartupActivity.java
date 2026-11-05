package io.github.gazehighlighter;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import org.jetbrains.annotations.NotNull;

public class GazeHighlighterStartupActivity implements StartupActivity.DumbAware {

    @Override
    public void runActivity(@NotNull Project project) {
        EditorFactory factory = EditorFactory.getInstance();

        // Attach to already-open editors
        for (Editor editor : factory.getAllEditors()) {
            if (project.equals(editor.getProject())) {
                attach(editor, project);
            }
        }

        // Attach to / detach from editors created or released from now on
        factory.addEditorFactoryListener(new EditorFactoryListener() {
            @Override
            public void editorCreated(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (project.equals(editor.getProject())) {
                    attach(editor, project);
                }
            }

            @Override
            public void editorReleased(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (project.equals(editor.getProject())) {
                    GazeDispatcher.getInstance(project).unregister(editor);
                }
            }
        }, project);

        // Camera connection and processing now start explicitly from experiment setup.
        // Opening another project must not pause an ongoing recording.
        if (!GazeRecordingService.isAnyRecording()) GazeFeatureSettings.getInstance().getState().paused = true;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            var options = GazeFeatureSettings.getInstance().getState();
            if (options.researchUiIntroVersion < 2) {
                options.researchUiIntroVersion = 2;
                NotificationGroupManager.getInstance().getNotificationGroup("Implicit Gaze")
                        .createNotification("Gaze Research", "Choose input → scenario → new or saved session. Start recording from the setup summary.", NotificationType.INFORMATION)
                        .addAction(NotificationAction.createSimple("Open Gaze Research", () -> OpenGazeResearchAction.open(project)))
                        .notify(project);
            }
        });
    }

    private void attach(Editor editor, Project project) {
        GazeDispatcher dispatcher = GazeDispatcher.getInstance(project);
        dispatcher.register(editor, project);
        // Mouse source: a thin adapter that forwards to the engine only in mouse mode.
        editor.addEditorMouseMotionListener(new GazeMouseListener(editor, dispatcher), project);
    }
}
