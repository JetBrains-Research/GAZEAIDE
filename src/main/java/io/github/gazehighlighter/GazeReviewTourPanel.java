package io.github.gazehighlighter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import java.awt.*;
import java.util.List;
import static io.github.gazehighlighter.GazeResearchPanel.*;

/** Overview → files → code. Gaze selects a step but never marks it reviewed or moves source. */
public final class GazeReviewTourPanel extends JPanel {
    interface Controller {
        GazeReviewTour tour(); ViewedRegion viewed(); String status();
        void loadOpenFiles(); void explain(); void navigate(GazeReviewTour.Step step); void export(); void backToExperiment();
        default boolean selectionPaused() { return false; }
        default void resumeSelection() {}
        default void changed() {}
        default void saveEdits() {}
        default String aiStatus() { return "JetBrains AI Chat"; }
        default void openAiChat() {}
    }
    private final Controller controller;
    private final Timer timer = new Timer(300, e -> refresh());
    private final JTabbedPane tabs = new JTabbedPane();
    private final JComboBox<String> files = new JComboBox<>();
    private final JComboBox<GazeReviewTour.Step> steps = new JComboBox<>();
    private final JCheckBox follow = new JCheckBox("Follow gaze / selected input", true), reviewed = new JCheckBox("I have reviewed this step");
    private final JTextArea overview = help("", 4), explanation = help("", 3), source = new JTextArea(10, 1), note = new JTextArea(4, 1);
    private final JTextArea state = help("", 2), location = help("", 2);
    private final JButton explain;
    private final JButton resume;
    private GazeReviewTour loaded;
    private GazeReviewTour.Step lastFollowed;
    private boolean loading;
    private String filter = "All files";
    public GazeReviewTourPanel(Controller controller) {
        super(new BorderLayout(0, 6)); this.controller = controller;
        explain = button("Ask JetBrains AI…", "tour-explain", controller::explain);
        resume = button("Enable gaze selection (no recording)", "tour-resume", controller::resumeSelection);
        setBorder(new EmptyBorder(10, 10, 10, 10));
        JPanel top = column(); JLabel title = new JLabel("Review walkthrough"); title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JButton back = button("Back to experiment", "tour-back", () -> { controller.saveEdits(); controller.backToExperiment(); });
        back.setToolTipText("Return to session and recording controls. This walkthrough stays open.");
        JButton export = button("Export HTML…", "tour-export", controller::export);
        export.setToolTipText("Save a portable copy of this walkthrough, notes and review marks.");
        top.add(title); top.add(row(back, export)); top.add(state); top.add(resume); add(top, BorderLayout.NORTH);
        JPanel intro = column(); intro.add(overview);
        intro.add(button("Explore files & code", "tour-explore", () -> tabs.setSelectedIndex(1)));
        intro.add(button("Load current open code files", "tour-load", () -> controller.loadOpenFiles()));
        intro.add(explain);
        intro.add(button("Open JetBrains AI Chat", "tour-provider", controller::openAiChat));
        intro.add(help("Ask previews a prompt with your task and up to 12 captured steps, selected step first. Copy it, then paste and send in native AI Chat. Choose AI Assistant or Junie Ask mode there. Replies stay in chat; add findings to Your notes.", 5));
        intro.add(help("Tour edits save locally with the session. Export a portable copy with explanations, notes, review marks and snapshots. Viewing trace and recording exports remain separate.", 4));
        tabs.addTab("Overview", scroll(intro));
        JPanel detail = column(); detail.add(files); detail.add(steps); detail.add(follow); detail.add(location);
        detail.add(row(button("Previous step", "tour-previous", () -> step(-1)), button("Next step", "tour-next", () -> step(1))));
        detail.add(button("Ask JetBrains AI about this step…", "tour-step-ai", controller::explain));
        detail.add(explanation);
        source.setEditable(false); source.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        note.setLineWrap(true); note.setWrapStyleWord(true);
        JTabbedPane text = new JTabbedPane(); text.addTab("Code snapshot", new JScrollPane(source)); text.addTab("Your notes", new JScrollPane(note));
        text.setPreferredSize(new Dimension(320, 220)); detail.add(text); detail.add(reviewed);
        detail.add(button("Open current source", "tour-source", () -> { if (selected() != null) controller.navigate(selected()); }));
        detail.add(help("Following gaze changes only the step shown here. It never scrolls the editor or marks a step reviewed. Pause Follow gaze to explore manually. Recorded versions must match current code for gaze selection and navigation.", 5));
        tabs.addTab("Files & code", scroll(detail)); tabs.setName("tour-tabs"); add(tabs, BorderLayout.CENTER);
        steps.setName("tour-steps"); files.setName("tour-files"); follow.setName("tour-follow"); reviewed.setName("tour-reviewed");
        files.setPrototypeDisplayValue("Current file path…");
        steps.setPrototypeDisplayValue(new GazeReviewTour.Step("S", new ViewedRegion("", 1, 1, "Code step…", "", 0, "")));
        source.setName("tour-code"); note.setName("tour-note");
        steps.addActionListener(e -> { if (!loading) showSelected(); });
        files.addActionListener(e -> { if (!loading) { filter = (String) files.getSelectedItem(); reloadSteps(null); } });
        reviewed.addActionListener(e -> { if (!loading && selected() != null) { selected().reviewed = reviewed.isSelected(); steps.repaint(); controller.changed(); } });
        note.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() { if (!loading && selected() != null) { selected().note = note.getText(); controller.changed(); } }
            public void insertUpdate(DocumentEvent e) { changed(); } public void removeUpdate(DocumentEvent e) { changed(); } public void changedUpdate(DocumentEvent e) { changed(); }
        });
        refresh();
    }
    public void startRefreshing() { timer.start(); }
    public void dispose() { timer.stop(); controller.saveEdits(); }
    public void refresh() {
        GazeReviewTour tour = controller.tour();
        if (loaded != tour) {
            loaded = tour; lastFollowed = null; loading = true; files.removeAllItems(); files.addItem("All files"); tour.files().forEach(files::addItem);
            filter = "All files"; loading = false; reloadSteps(null);
        }
        state.setText(controller.status());
        resume.setVisible(controller.selectionPaused());
        overview.setText("Review goal: " + (tour.goal.isBlank() ? "not supplied" : tour.goal) + "\nScope: " + tour.scope
                + "\n" + tour.files().size() + " files · " + tour.steps.size() + " steps\nAI integration: " + controller.aiStatus() + "\n\n" + tour.overview
                + (tour.provider.isBlank() ? "" : "\nSaved AI draft from earlier version: " + tour.provider));
        explain.setEnabled(!tour.steps.isEmpty());
        if (follow.isSelected()) {
            GazeReviewTour.Step target = tour.match(controller.viewed());
            if (target != null && (target != selected() || target != lastFollowed)) {
                loading = true; files.setSelectedItem(target.region.filePath()); filter = target.region.filePath(); loading = false;
                reloadSteps(target); tabs.setSelectedIndex(1);
            }
            lastFollowed = target;
        } else lastFollowed = null;
        // Explanations may have arrived asynchronously; notes are not overwritten by polling.
        showExplanation(); revalidate(); repaint();
    }
    private void reloadSteps(GazeReviewTour.Step preferred) {
        loading = true; steps.removeAllItems();
        loaded.steps.stream().filter(s -> "All files".equals(filter) || s.region.filePath().equals(filter)).forEach(steps::addItem);
        if (preferred != null) steps.setSelectedItem(preferred); loading = false; showSelected();
    }
    private GazeReviewTour.Step selected() { return (GazeReviewTour.Step) steps.getSelectedItem(); }
    GazeReviewTour.Step selectedStep() { return selected(); }
    private void showSelected() {
        loading = true; var s = selected();
        source.setText(s == null ? "No code steps captured. Open code files, then Load current open code files." : s.region.text()); source.setCaretPosition(0);
        note.setText(s == null ? "" : s.note); note.setEnabled(s != null);
        reviewed.setSelected(s != null && s.reviewed); reviewed.setEnabled(s != null);
        location.setText(s == null ? "No step selected" : s.id + " · " + s.region.location() + "\nSnapshot version " + s.region.documentStamp() + " · " + s.region.source());
        loading = false; showExplanation();
    }
    private void showExplanation() {
        var s = selected();
        explanation.setText(s == null ? "" : s.behavior.isBlank() ? "Ask JetBrains AI about this step; the reply opens in native chat. Add useful findings to Your notes."
                : "Behavior: " + s.behavior + "\n\nPossible rationale (inference): " + s.rationale + "\n\nReview check: " + s.check + "\n\nMissing context: " + s.uncertainty);
    }
    private void step(int delta) { if (steps.getItemCount() > 0) { follow.setSelected(false); steps.setSelectedIndex(Math.max(0, Math.min(steps.getItemCount() - 1, steps.getSelectedIndex() + delta))); } }
    private static JButton button(String title, String name, Runnable action) {
        JButton button = new JButton(title); button.setName(name); button.addActionListener(e -> action.run()); return button;
    }
}
