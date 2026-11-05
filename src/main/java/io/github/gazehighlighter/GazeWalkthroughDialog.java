package io.github.gazehighlighter;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Editable route and standalone export. No AI requests, source changes, or automatic navigation. */
public final class GazeWalkthroughDialog extends DialogWrapper {
    private final Project project;
    private final List<GazeWalkthrough.Stop> stops;
    private final GazeSessionMetadata metadata;
    private final DefaultListModel<GazeWalkthrough.Stop> model = new DefaultListModel<>();
    private JList<GazeWalkthrough.Stop> list;
    private JTextArea source, note;
    private JLabel location;
    private boolean loading;

    public GazeWalkthroughDialog(Project project, GazeSession session) {
        super(project, false); this.project = project;
        metadata = session.metadata;
        stops = GazeWalkthrough.build(session, GazeFeatureSettings.getInstance().getState().stableMs);
        stops.forEach(model::addElement);
        setTitle("Implicit — Recorded Viewing Trace"); setModal(false); setResizable(true); init();
    }
    @Override protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setPreferredSize(new Dimension(JBUI.scale(940), JBUI.scale(620)));
        root.add(new JLabel("<html>Route from recorded viewing; adjacent visits are grouped. "
                + "Select stops to inspect snapshots, add notes, or remove accidental stops.<br>"
                + "Viewing is not proof of review or understanding. Edits to this route are saved only when you export.</html>"), BorderLayout.NORTH);
        list = new JList<>(model); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) select(); });
        source = new JTextArea(); source.setEditable(false); source.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        note = new JTextArea(4, 40); note.setLineWrap(true); note.setWrapStyleWord(true);
        note.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { saveNote(); }
            public void removeUpdate(DocumentEvent e) { saveNote(); }
            public void changedUpdate(DocumentEvent e) { saveNote(); }
        });
        location = new JLabel("No sustained region visits in this recording.");
        JPanel detail = new JPanel(new BorderLayout(6, 6));
        detail.add(location, BorderLayout.NORTH); detail.add(new JBScrollPane(source), BorderLayout.CENTER);
        JPanel notes = new JPanel(new BorderLayout()); notes.add(new JLabel("Your annotation:"), BorderLayout.NORTH);
        notes.add(new JBScrollPane(note), BorderLayout.CENTER); detail.add(notes, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JBScrollPane(list), detail);
        split.setResizeWeight(0.3); root.add(split, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(buttons, "Previous", () -> step(-1)); button(buttons, "Next", () -> step(1));
        button(buttons, "Open current source", this::navigate);
        button(buttons, "Remove stop", () -> {
            int i = list.getSelectedIndex();
            if (i >= 0) { stops.remove(i); model.remove(i); if (!stops.isEmpty()) list.setSelectedIndex(Math.min(i, stops.size() - 1)); }
        });
        button(buttons, "Export viewing trace…", this::export);
        root.add(buttons, BorderLayout.SOUTH);
        if (!stops.isEmpty()) list.setSelectedIndex(0); else { note.setEnabled(false); source.setText("Record a session while viewing code, then open its viewing trace."); }
        return root;
    }
    private static void button(JPanel panel, String title, Runnable action) {
        JButton b = new JButton(title); b.addActionListener(e -> action.run()); panel.add(b);
    }
    private void saveNote() { if (!loading && list.getSelectedValue() != null) list.getSelectedValue().note = note.getText(); }
    private void select() {
        GazeWalkthrough.Stop stop = list.getSelectedValue();
        loading = true;
        if (stop != null) {
            location.setText("<html>" + GazeWalkthrough.escape(stop.region.location()) + "<br>"
                    + GazeWalkthrough.escape(stop.region.source()) + " · exposure " + stop.dwellMs + " ms · snapshot version "
                    + stop.region.documentStamp() + "</html>");
            source.setText(stop.region.text()); source.setCaretPosition(0); note.setText(stop.note); note.setEnabled(true);
        } else { source.setText(""); note.setText(""); note.setEnabled(false); location.setText("No stop selected."); }
        loading = false;
    }
    private void step(int delta) {
        if (!stops.isEmpty()) list.setSelectedIndex(Math.max(0, Math.min(stops.size() - 1, list.getSelectedIndex() + delta)));
    }
    private void navigate() {
        GazeWalkthrough.Stop stop = list.getSelectedValue(); if (stop == null) return;
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(stop.region.filePath());
        var doc = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
        if (doc == null || doc.getModificationStamp() != stop.region.documentStamp() || !GazeReviewTour.matchesSource(doc.getText(), stop.region)) {
            GazeNotify.warn(project, "Current source does not match this snapshot, or is unavailable. The recorded snapshot remains visible; its old line numbers are not used for navigation."); return;
        }
        new OpenFileDescriptor(project, file, stop.region.firstLine() - 1, 0).navigate(true);
    }
    private void export() {
        if (stops.isEmpty()) { GazeNotify.warn(project, "There are no viewing trace stops to export."); return; }
        var path = GazeHtmlSaveDialog.choose(project, getContentPanel(), "Export viewing trace", "gaze-viewing-trace.html");
        if (path == null) return;
        try {
            Files.writeString(path, GazeWalkthrough.html(project.getName(), stops, metadata), StandardCharsets.UTF_8);
            GazeNotify.info(project, "Viewing trace exported to " + path);
        } catch (IOException ex) { GazeNotify.warn(project, "Could not save the viewing trace: " + ex.getMessage()); }
    }
    @Override protected Action[] createActions() { getOKAction().putValue(Action.NAME, "Close"); return new Action[]{getOKAction()}; }
}
