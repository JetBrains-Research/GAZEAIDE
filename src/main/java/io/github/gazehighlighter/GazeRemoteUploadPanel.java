package io.github.gazehighlighter;

import javax.swing.*;
import java.awt.*;

/** Native, testable connection editor. No account is created and no data is sent by editing fields. */
final class GazeRemoteUploadPanel extends JPanel {
    static final String NOTICE = "Research feature — separate setup and testing required. Choose the collected variables and storage for your research questions; establish participant consent, ethics review where required, institutional permission, access, retention and withdrawal procedures. A successful connection test does not validate the study or approve cloud collection.";
    enum Action { SAVE, TEST, PREVIEW, UPLOAD, RETRY, CLEAR, DISCONNECT, SAVE_RECEIVER }
    interface Listener { void perform(Action action); }
    private final JCheckBox enabled = check("Enable remote upload for this project", "remote-enabled");
    private final JComboBox<GazeRemoteConnection.Connector> connector = new JComboBox<>(GazeRemoteConnection.Connector.values());
    private final JTextField endpoint = field("remote-endpoint"), study = field("remote-study"), participant = field("remote-participant"), task = field("remote-task");
    private final JPasswordField token = new JPasswordField();
    private final JCheckBox automatic = check("Upload automatically after future recordings stop", "remote-automatic");
    private final JCheckBox acknowledge = check("I understand that study-specific setup and separate testing are required", "remote-acknowledge");
    private final JTextArea status = GazeResearchPanel.help("", 4), connectorHelp = GazeResearchPanel.help("", 3);
    private final java.util.List<JButton> buttons = new java.util.ArrayList<>();
    GazeRemoteUploadPanel(GazeRemoteConnection connection, String setup, Listener listener) {
        super(new BorderLayout(0, 8)); setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JTabbedPane tabs = new JTabbedPane(); JPanel form = GazeResearchPanel.column();
        form.add(GazeResearchPanel.help("Optional remote study upload. Off by default. Save connection applies edits; Close discards unsaved edits. Local recording works without a connection.", 2));
        form.add(enabled); connector.setName("remote-connector"); form.add(form("Connector", connector)); form.add(connectorHelp);
        form.add(form("Upload address", endpoint)); form.add(form("Study code", study)); form.add(form("Participant code", participant)); form.add(form("Task code (optional)", task));
        token.setName("remote-token"); form.add(form("Participant token", token));
        form.add(GazeResearchPanel.help("Use a separate upload token for each participant, supplied by the receiver. A blank token keeps the saved one for this connection. Tokens use IDE password storage; they are not saved in exports. Study/task codes are separate from local free-text labels.", 4));
        form.add(automatic);
        form.add(GazeResearchPanel.help(NOTICE, 5));
        JPanel connectionPage = new JPanel(new BorderLayout()); connectionPage.add(GazeResearchPanel.scroll(form), BorderLayout.CENTER);
        JPanel connectionActions = GazeResearchPanel.column(); connectionActions.add(acknowledge);
        connectionActions.add(GazeResearchPanel.row(button("Save connection", "remote-save", Action.SAVE, listener), button("Test connection (synthetic)", "remote-test", Action.TEST, listener)));
        connectionPage.add(connectionActions, BorderLayout.SOUTH);
        tabs.addTab("Connection", connectionPage);
        JPanel uploads = GazeResearchPanel.column();
        uploads.add(GazeResearchPanel.help("Stop a recording or reopen a saved one. Preview works before connecting and sends nothing. Before upload, save your connection and check that its participant/task codes match the recording; local labels are excluded. Test with synthetic recordings before recruiting participants.", 5));
        uploads.add(button("Preview upload data…", "remote-preview", Action.PREVIEW, listener));
        uploads.add(button("Upload selected recording", "remote-upload", Action.UPLOAD, listener));
        uploads.add(GazeResearchPanel.help("A failed attempt keeps its reduced payload locally with a stable upload ID. Nothing retries on startup or a timer. Retry sends only to the original connection, study and participant.", 4));
        uploads.add(button("Retry pending uploads", "remote-retry", Action.RETRY, listener));
        uploads.add(button("Delete pending uploads…", "remote-clear", Action.CLEAR, listener));
        uploads.add(GazeResearchPanel.help("Disconnect disables uploads and removes this connection's stored token. It does not delete local recordings or data already held by the researcher.", 3));
        uploads.add(button("Disconnect & remove token", "remote-disconnect", Action.DISCONNECT, listener));
        tabs.addTab("Upload & queue", GazeResearchPanel.scroll(uploads));
        JPanel data = GazeResearchPanel.column();
        data.add(GazeResearchPanel.help("UPLOAD DATA — SCHEMA 1\nParticipant/study/task codes; plugin version; relative viewing-event times and durations; within-session opaque file/region IDs and line positions; input provenance; scenario, settings, calibration-quality and personalization summaries; configuration changes.\n\nEXCLUDED\nSource code, paths, project names, local session labels, identifiers, notes, AI prompts/answers, raw gaze samples, video, absolute event times and calibration coefficients. File IDs restart in each session; source identity cannot be reconstructed across sessions.\n\nThese are pseudonymous processed viewing events, not proof of fixations or understanding. Code layout and timing can still be sensitive. The server sees network metadata, including IP addresses. Its logging, access and retention need separate configuration.\n\nFailed uploads stay in a local reduced-data queue. Retry is explicit, including after IDE restart. Queued data is bound to the original receiver/study/participant. Disconnect stops new sends; it does not delete cloud data. Full local recordings still contain source snapshots.", 20));
        data.add(GazeResearchPanel.help(setup, 26)); data.add(button("Save Google Drive receiver template…", "remote-save-receiver", Action.SAVE_RECEIVER, listener));
        tabs.addTab("Data & setup", GazeResearchPanel.scroll(data)); add(tabs, BorderLayout.CENTER);
        status.setName("remote-status"); add(status, BorderLayout.SOUTH);
        loadConnection(connection);
        connector.addActionListener(e -> updateConnectorHelp()); updateConnectorHelp();
    }
    void loadConnection(GazeRemoteConnection connection) {
        enabled.setSelected(connection.enabled()); connector.setSelectedItem(connection.connector()); endpoint.setText(connection.endpoint());
        study.setText(connection.studyId()); participant.setText(connection.participantId()); task.setText(connection.taskCode());
        automatic.setSelected(connection.automatic()); acknowledge.setSelected(connection.acknowledged());
        clearToken(); updateConnectorHelp();
    }
    private void updateConnectorHelp() {
        connectorHelp.setText(connector.getSelectedItem() == GazeRemoteConnection.Connector.GOOGLE_DRIVE
                ? "Connect through a researcher-owned Apps Script receiver. Paste its /exec address, not a folder link. No Google account sign-in is built into this plugin. See Data & setup."
                : "Connect to a research-managed HTTPS receiver implementing the upload protocol. Supabase, S3 and other databases require a server-side receiver; do not paste a database password or admin key.");
    }
    GazeRemoteConnection draft() {
        return new GazeRemoteConnection((GazeRemoteConnection.Connector) connector.getSelectedItem(), endpoint.getText(), study.getText(), participant.getText(), task.getText(), enabled.isSelected(), automatic.isSelected(), acknowledge.isSelected());
    }
    String replacementToken() { char[] value = token.getPassword(); try { return new String(value).strip(); } finally { java.util.Arrays.fill(value, '\0'); } }
    void clearToken() { token.setText(""); }
    JButton saveButton() { return buttons.stream().filter(b -> "remote-save".equals(b.getName())).findFirst().orElseThrow(); }
    void showStatus(String text, boolean busy) { status.setText(text); buttons.forEach(b -> b.setEnabled(!busy)); }
    private JButton button(String text, String name, Action action, Listener listener) {
        JButton b = new JButton(text); b.setName(name); b.addActionListener(e -> listener.perform(action)); buttons.add(b); return b;
    }
    private static JCheckBox check(String text, String name) { JCheckBox c = new JCheckBox(text); c.setName(name); return c; }
    private static JTextField field(String name) { JTextField f = new JTextField(22); f.setName(name); return f; }
    private static JPanel form(String text, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(8, 0)); JLabel label = new JLabel(text); label.setLabelFor(control); label.setPreferredSize(new Dimension(135, 24));
        row.add(label, BorderLayout.WEST); row.add(control, BorderLayout.CENTER); row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0)); return row;
    }
}
