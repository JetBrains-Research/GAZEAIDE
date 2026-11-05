package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import javax.swing.*;
import java.awt.Dimension;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class GazeRemoteUploadDialog extends DialogWrapper {
    private final Project project;
    private final GazeRemoteUploadService service;
    private final GazeRemoteUploadPanel panel;
    private final Timer timer;
    private String feedback = "";
    GazeRemoteUploadDialog(Project project) {
        super(project); this.project = project; service = GazeRemoteUploadService.getInstance(project);
        panel = new GazeRemoteUploadPanel(service.connection(), resource("/remote/setup.txt"), this::perform);
        panel.setPreferredSize(new Dimension(690, 650)); setTitle("Remote study upload — experimental"); setOKButtonText("Close"); init();
        getRootPane().setDefaultButton(panel.saveButton());
        timer = new Timer(500, e -> panel.showStatus(service.isBusy() || feedback.isBlank() ? service.status() : feedback, service.isBusy()));
        timer.start(); panel.showStatus(service.status(), service.isBusy());
    }
    @Override protected JComponent createCenterPanel() { return panel; }
    @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }
    @Override protected void dispose() { if (timer != null) timer.stop(); super.dispose(); }
    private void perform(GazeRemoteUploadPanel.Action action) {
        feedback = "";
        if (action == GazeRemoteUploadPanel.Action.SAVE) {
            service.save(panel.draft(), panel.replacementToken(), this::complete); return;
        }
        if (action == GazeRemoteUploadPanel.Action.PREVIEW) { preview(); return; }
        if (action == GazeRemoteUploadPanel.Action.SAVE_RECEIVER) { saveReceiver(); return; }
        if (action != GazeRemoteUploadPanel.Action.DISCONNECT && action != GazeRemoteUploadPanel.Action.CLEAR && !panel.draft().equals(service.connection())) {
            complete("Save the edited connection first. Tests and uploads always use saved settings."); return;
        }
        switch (action) {
            case TEST -> service.test(this::complete);
            case UPLOAD -> service.uploadSelected(this::complete);
            case RETRY -> service.retry(this::complete);
            case DISCONNECT -> service.disconnect(message -> { panel.loadConnection(service.connection()); complete(message); });
            case CLEAR -> {
                if (Messages.showYesNoDialog(project, "Delete all pending reduced upload payloads on this computer? Full local recordings remain. Already uploaded cloud data is not deleted.", "Delete pending uploads", Messages.getQuestionIcon()) == Messages.YES)
                    service.clearPending(this::complete);
            }
            default -> {}
        }
        panel.showStatus(service.status(), service.isBusy());
    }
    private void complete(String message) {
        if (isDisposed()) return;
        feedback = message; panel.clearToken(); panel.showStatus(message, service.isBusy());
    }
    private void preview() {
        var session = GazeRecordingService.getInstance(project).getLastSession();
        if (session == null || GazeRecordingService.getInstance(project).isRecording()) { complete("Stop a recording or reopen a saved one to preview its reduced upload data."); return; }
        // A local preview is useful before the researcher has chosen a storage service.
        var connection = panel.draft();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String json = service.preview(session, connection);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed() || isDisposed()) return;
                    new DialogWrapper(project) {
                        { setTitle("Reduced upload preview — nothing sent"); setOKButtonText("Close"); init(); }
                        @Override protected JComponent createCenterPanel() {
                            JTextArea text = new JTextArea(json); text.setEditable(false); text.setCaretPosition(0);
                            JScrollPane scroll = new JScrollPane(text); scroll.setPreferredSize(new Dimension(750, 540)); return scroll;
                        }
                        @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }
                    }.show();
                });
            } catch (Exception ex) { ApplicationManager.getApplication().invokeLater(() -> complete("Could not preview the reduced dataset. Check the local upload queue and session size.")); }
        });
    }
    private void saveReceiver() {
        var chooser = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Save Google Drive receiver", "Paste this template into a researcher-owned Apps Script project. Setup is described in Data & setup.", "js"), panel);
        var selected = chooser.save((Path) null, "gazeaide-drive-receiver.js"); if (selected == null) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String message;
            try { Files.writeString(selected.getFile().toPath(), resource("/remote/google-drive-receiver.js")); message = "Receiver template saved. No cloud service has been configured or contacted."; }
            catch (Exception ex) { message = "Could not save the receiver template. Check the selected file location."; }
            String result = message; ApplicationManager.getApplication().invokeLater(() -> complete(result));
        });
    }
    static String resource(String name) {
        try (var stream = GazeRemoteUploadDialog.class.getResourceAsStream(name)) {
            return stream == null ? "Setup resource is missing. See docs/remote-upload.md in the local plugin project." : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) { return "Setup resource could not be read. See docs/remote-upload.md."; }
    }
}
