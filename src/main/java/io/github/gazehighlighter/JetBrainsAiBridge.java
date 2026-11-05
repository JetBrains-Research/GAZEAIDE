package io.github.gazehighlighter;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.TextRange;
import com.google.gson.GsonBuilder;
import java.util.Map;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/** Optional native action handoff. No model, credentials, HTTP client or private AI API. */
public final class JetBrainsAiBridge {
    private JetBrainsAiBridge() {}
    // Verified in installed AI Assistant / unified Junie. IDs can vary across IDE versions.
    static final String ASK = "AIAssistantAskInChatAction";
    static final String CHAT = "ActivateElectroJunToolWindowToolWindow";
    private static AnAction availableAction() {
        ActionManager manager = ActionManager.getInstance();
        AnAction ask = manager.getAction(ASK);
        return ask != null ? ask : manager.getAction(CHAT);
    }
    public static String status() {
        return availableAction() == null ? "AI Chat not detected. Enable JetBrains AI Assistant or Junie in Settings → Plugins."
                : "JetBrains AI Chat detected. Sign in and choose the assistant/model in native chat.";
    }
    public static void openChat(Project project) {
        String error = handoff(project, null, null);
        if (error != null) GazeNotify.warn(project, error);
    }
    public static void askViewed(Project project) {
        GazeContextService context = GazeContextService.getInstance(project);
        ViewedRegion region = GazeModeService.getInstance(project).getViewedRegion();
        // A captured dwell remains available after moving attention to this button.
        String prompt = context.preparedPrompt();
        if (prompt != null) region = context.preparedRegion();
        else if (region != null) prompt = GazePrompt.build(region.text(), region.text(), null,
                GazeFeatureSettings.getInstance().getState().historyContext ? context.recent(region.source()) : java.util.List.of(),
                GazeResearchSettings.getInstance(project).reviewPurpose());
        if (prompt == null) { GazeNotify.warn(project, "Look at a code block first, or choose a captured step in Review walkthrough."); return; }
        showRequest(project, GazePrompt.SYSTEM + "\n\n" + prompt, region);
    }
    public static void showRequest(Project project, String prompt, @Nullable ViewedRegion region) {
        GazeContextService.getInstance(project).interaction("Prompt preview opened. Sending and replies in native chat are not observed.");
        new RequestDialog(project, prompt, region).show();
    }
    private static @Nullable String handoff(Project project, @Nullable String prompt, @Nullable ViewedRegion region) {
        if (project.isDisposed()) return "The project has closed.";
        AnAction action = availableAction();
        if (action == null) return "Enable JetBrains AI Assistant / Junie in Settings → Plugins, then open and sign in to AI Chat. This IDE exposes no supported chat action.";
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        // Use a matching open editor only. Never attach today's unrelated selection to an old snapshot.
        if (region != null) {
            editor = null;
            for (Editor open : com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors()) {
                var file = FileDocumentManager.getInstance().getFile(open.getDocument());
                if (open.getProject() == project && !open.isDisposed() && file != null
                        && file.getPath().equals(region.filePath())
                        && open.getDocument().getModificationStamp() == region.documentStamp()
                        && GazeReviewTour.matchesSource(open.getDocument().getText(), region)) { editor = open; break; }
            }
        }
        var builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project);
        if (editor != null) {
            builder.add(CommonDataKeys.EDITOR, editor).add(PlatformDataKeys.CONTEXT_COMPONENT, editor.getContentComponent());
            var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file != null) builder.add(CommonDataKeys.VIRTUAL_FILE, file);
            var psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            if (psi != null) builder.add(CommonDataKeys.PSI_FILE, psi);
        }
        DataContext data = builder.build();
        AnActionEvent event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLWINDOW_CONTENT, data);
        try {
            // Same availability/ignore/indexing checks as invoking the action through IntelliJ.
            // Do not bypass a disabled Ask action by trying a less restrictive fallback.
            if (!ActionUtil.lastUpdateAndCheckDumb(action, event, false) || !event.getPresentation().isEnabledAndVisible())
                return "JetBrains AI Chat is currently unavailable for this context. Check AI setup, indexing and .aiignore in IntelliJ. Nothing was sent or copied.";
            if (editor != null && region != null) {
                var document = editor.getDocument();
                editor.getSelectionModel().setSelection(document.getLineStartOffset(region.firstLine() - 1),
                        document.getLineEndOffset(region.lastLine() - 1));
            }
            if (prompt != null) CopyPasteManager.getInstance().setContents(new StringSelection(prompt));
            ActionUtil.performActionDumbAwareWithCallbacks(action, event);
            if (prompt != null) GazeContextService.getInstance(project).interaction("Prompt copied and native chat opened. Paste/send there; sending and replies are not observed.");
            if (prompt != null) GazeNotify.info(project, "Prompt copied. Paste it into AI Chat and send. Choose AI Assistant or Junie Ask mode there. Replies stay in native chat.");
            return null;
        } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) { throw cancelled; }
        catch (RuntimeException ex) { return "Could not open JetBrains AI Chat. Open it from IntelliJ and try again. No automatic request was sent."; }
    }
    private static @Nullable ViewedRegion viewport(Project project) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || editor.isDisposed()) return null;
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null || editor.getDocument().getLineCount() == 0) return null;
        Rectangle area = editor.getScrollingModel().getVisibleArea();
        var doc = editor.getDocument();
        int first = Math.max(0, Math.min(doc.getLineCount()-1, editor.xyToLogicalPosition(new Point(area.x, area.y)).line));
        int last = Math.max(first, Math.min(Math.min(doc.getLineCount()-1, first+119), editor.xyToLogicalPosition(new Point(area.x, area.y+Math.max(0,area.height-1))).line));
        return new ViewedRegion(file.getPath(), first+1, last+1, "Visible editor viewport",
                doc.getText(new TextRange(doc.getLineStartOffset(first), doc.getLineEndOffset(last))), doc.getModificationStamp(), "VIEWPORT");
    }
    private static final class RequestDialog extends DialogWrapper {
        private final Project project;
        private final ViewedRegion region;
        private final JCheckBox attach = new JCheckBox("Attach recent context", true);
        private final JCheckBox saveResearch = new JCheckBox("Save research example locally", true);
        private final Map<String,Object> context;
        private final String recordingId;
        private final JTextArea prompt = new JTextArea(18, 66);
        private final JTextArea message = GazeResearchPanel.help("Review or edit the prompt below. Copy prompt & open AI Chat copies it to your clipboard. Paste and send in native chat; choose Junie Ask mode for explanations. Replies are not imported into the walkthrough. Captured code may be older than current files.", 4);
        RequestDialog(Project project, String text, ViewedRegion region) {
            super(project); this.project = project; this.region = region;
            var recording = GazeRecordingService.getInstance(project);
            context = GazeAgentContext.build(recording.contextSnapshot(), region, viewport(project),
                    GazeResearchController.captureMetadata(project).initialConfiguration(), System.currentTimeMillis());
            recordingId = recording.isRecording() ? "intellij-" + recording.getStartMs() : null;
            setTitle("Ask JetBrains AI about captured code"); setOKButtonText("Copy prompt & open AI Chat");
            prompt.setText(text); prompt.setCaretPosition(0); prompt.setLineWrap(true); prompt.setWrapStyleWord(true);
            init();
        }
        @Override protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 8)); panel.add(message, BorderLayout.NORTH);
            panel.add(new JScrollPane(prompt), BorderLayout.CENTER);
            JPanel options = new JPanel(); options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
            options.add(attach); options.add(saveResearch);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton preview = new JButton("Preview attachment");
            preview.addActionListener(e -> {
                JTextArea text = new JTextArea(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(context), 22, 70);
                text.setEditable(false); text.setCaretPosition(0);
                JOptionPane.showMessageDialog(getContentPane(), new JScrollPane(text), "Recent context · captured at preview open", JOptionPane.PLAIN_MESSAGE);
            });
            JButton records = new JButton("Research records…"); records.addActionListener(e -> GazeAgentRecordsAction.open(project));
            buttons.add(preview); buttons.add(records); options.add(buttons);
            options.add(GazeResearchPanel.help("Recent exposure (up to 10 minutes) and visible code are relevance hints. Local records contain source text; reduced study uploads exclude them. Native delivery and answers remain unobserved.", 3));
            panel.add(options, BorderLayout.SOUTH); panel.setPreferredSize(new Dimension(720, 600)); return panel;
        }
        @Override protected void doOKAction() {
            if (prompt.getText().isBlank()) { message.setText("Enter a question or keep the prepared prompt."); return; }
            final String text;
            try { text = GazeAgentContext.compose(prompt.getText(), context, attach.isSelected()); }
            catch (IllegalArgumentException ex) { message.setText(ex.getMessage()); return; }
            String error = handoff(project, text, region);
            if (error == null) {
                if (saveResearch.isSelected()) {
                    var record = GazeAgentJournal.example(prompt.getText(), context, attach.isSelected(), recordingId, System.currentTimeMillis());
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try { new GazeAgentJournal(GazeSessionLibrary.getInstance(project).agentDirectory()).save(record); }
                        catch (Exception ex) { ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed()) GazeNotify.warn(project, "Prompt copied, but the local research record could not be saved."); }); }
                    });
                }
                super.doOKAction();
            } else message.setText(error);
        }
    }
}
