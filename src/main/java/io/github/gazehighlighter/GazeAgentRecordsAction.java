package io.github.gazehighlighter;

import com.google.gson.GsonBuilder;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/** Inspect/export local examples without pretending to observe native AI replies. */
public final class GazeAgentRecordsAction extends AnAction {
    @Override public void actionPerformed(AnActionEvent event) { if (event.getProject()!=null) open(event.getProject()); }
    static void open(Project project) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var journal = new GazeAgentJournal(GazeSessionLibrary.getInstance(project).agentDirectory());
            try {
                var records = journal.load();
                ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed()) new RecordsDialog(project, journal, records).show(); });
            } catch (Exception ex) { ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed()) GazeNotify.warn(project, "Research records could not be loaded."); }); }
        });
    }
    private static final class RecordsDialog extends DialogWrapper {
        private final Project project;
        private final GazeAgentJournal journal;
        private final List<Map<String,Object>> records;
        private final JComboBox<String> choose = new JComboBox<>();
        private final JComboBox<String> label = new JComboBox<>(new String[]{"unlabelled", "helpful", "mixed", "not_helpful"});
        private final JTextArea preview = new JTextArea(14, 75), answer = new JTextArea(5, 65), note = new JTextArea(3, 65);
        private final JLabel status = new JLabel("Answers are unknown unless you paste one here. Feedback is a user report, not a correctness score.");
        private final JButton save = new JButton("Save answer / feedback"), export = new JButton("Export dataset JSONL…");
        RecordsDialog(Project project, GazeAgentJournal journal, List<Map<String,Object>> records) {
            super(project); this.project=project; this.journal=journal; this.records=records;
            setTitle("GAZEAIDE · Local agent research records"); setOKButtonText("Close");
            for(var r:records) choose.addItem(new java.util.Date(((Number)r.get("createdAtMs")).longValue())+" · "+String.valueOf(r.get("question")).replace('\n',' ').substring(0,Math.min(60,String.valueOf(r.get("question")).length())));
            preview.setEditable(false); answer.setLineWrap(true); note.setLineWrap(true);
            choose.addActionListener(e -> display()); save.addActionListener(e -> save()); export.addActionListener(e -> export());
            init(); display();
        }
        private Map<String,Object> selected() { int i=choose.getSelectedIndex(); return i<0?null:records.get(i); }
        private void display() {
            var r=selected(); preview.setText(r==null?"No examples yet. Use Ask JetBrains AI… and keep Save research example locally enabled.":new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(r)); preview.setCaretPosition(0);
            var feedback=r!=null&&r.get("feedback") instanceof Map<?,?> m?m:null;
            label.setSelectedItem(feedback==null?"unlabelled":feedback.get("label"));
            note.setText(feedback==null||feedback.get("note")==null?"":String.valueOf(feedback.get("note")));
            var response=r!=null&&r.get("response") instanceof Map<?,?> m?m:null;
            answer.setText(response==null?"":String.valueOf(response.get("text"))); answer.setCaretPosition(0);
            save.setEnabled(r!=null); export.setEnabled(!records.isEmpty());
        }
        @Override protected JComponent createCenterPanel() {
            JPanel p=new JPanel(); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS)); p.add(choose); p.add(new JScrollPane(preview));
            p.add(new JLabel("Optional answer pasted from native chat (marked user_transcription):")); p.add(new JScrollPane(answer));
            p.add(new JLabel("Answer helpfulness:")); p.add(label); p.add(new JLabel("Optional feedback:")); p.add(new JScrollPane(note));
            JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT)); buttons.add(save); buttons.add(export); p.add(buttons); p.add(status);
            p.setPreferredSize(new Dimension(810,650)); return p;
        }
        private void busy(boolean busy) { choose.setEnabled(!busy); save.setEnabled(!busy); export.setEnabled(!busy); answer.setEditable(!busy); note.setEditable(!busy); label.setEnabled(!busy); }
        private void save() {
            var record=selected(); if(record==null)return;
            try { GazeAgentJournal.feedback(record,(String)label.getSelectedItem(),note.getText(),answer.getText()); }
            catch(IllegalArgumentException ex){status.setText(ex.getMessage());return;}
            busy(true);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String result; try { journal.save(record); result="Saved locally. Native delivery remains unobserved; the pasted answer is labelled user_transcription."; }
                catch(Exception ex){result="Save failed. Keep this dialog open and try again.";}
                String message=result; ApplicationManager.getApplication().invokeLater(() -> { if(!isDisposed()){busy(false);display();status.setText(message);} });
            });
        }
        private void export() {
            var wrapper=FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Export agent research dataset", "Contains captured source, prompts, optional answers and feedback. Keep participant data private.", "jsonl"),project).save((com.intellij.openapi.vfs.VirtualFile)null,"gazeaide-agent-examples.jsonl");
            if(wrapper==null)return;
            String content=GazeAgentJournal.jsonl(records); Path destination=wrapper.getFile().toPath(); busy(true);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String result; try { Files.writeString(destination,content,StandardCharsets.UTF_8); result="Exported "+records.size()+" examples to "+destination; }
                catch(Exception ex){result="Export failed. Choose a writable destination.";}
                String message=result; ApplicationManager.getApplication().invokeLater(() -> { if(!isDisposed()){busy(false);status.setText(message);} });
            });
        }
    }
}
