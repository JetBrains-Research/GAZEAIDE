package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.Dimension;

public final class ShowGazeContextAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject(); if (project == null) return;
        GazeSession session = GazeRecordingService.getInstance(project).contextSnapshot();
        String captured = GazeContextService.getInstance(project).inspect(session).text();
        new DialogWrapper(project) {
            { setTitle("Implicit — AI context for selected session"); setResizable(true); init(); }
            @Override protected JComponent createCenterPanel() {
                JTextArea text = new JTextArea(captured); text.setEditable(false); text.setCaretPosition(0);
                text.setLineWrap(true); text.setWrapStyleWord(true);
                JBScrollPane scroll = new JBScrollPane(text); scroll.setPreferredSize(new Dimension(800, 500)); return scroll;
            }
        }.show();
    }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabledAndVisible(e.getProject() != null); }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
