package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class AskJetBrainsAiAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) JetBrainsAiBridge.askViewed(event.getProject());
    }
}
