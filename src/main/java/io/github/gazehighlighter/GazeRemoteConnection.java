package io.github.gazehighlighter;

import java.net.URI;
import java.util.UUID;

/** Non-secret, project-scoped upload configuration. Never a cloud administration credential. */
record GazeRemoteConnection(Connector connector, String endpoint, String studyId, String participantId,
                            String taskCode, boolean enabled, boolean automatic, boolean acknowledged) {
    enum Connector {
        HTTPS("Research server (HTTPS)"), GOOGLE_DRIVE("Google Drive (Apps Script receiver)");
        private final String title;
        Connector(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }
    static GazeRemoteConnection defaults() {
        return new GazeRemoteConnection(Connector.HTTPS, "", "", "P-" + UUID.randomUUID(), "", false, false, false);
    }
    GazeRemoteConnection {
        endpoint = endpoint == null ? "" : endpoint.strip();
        studyId = studyId == null ? "" : studyId.strip();
        participantId = participantId == null ? "" : participantId.strip();
        taskCode = taskCode == null ? "" : taskCode.strip();
    }
    String problem() {
        if (connector == null) return "Choose a connector.";
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443))
                return "Use an HTTPS address without a login, query, fragment or custom port.";
            if (connector == Connector.GOOGLE_DRIVE && (!"script.google.com".equalsIgnoreCase(uri.getHost())
                    || !uri.getPath().matches("/macros/s/[A-Za-z0-9_-]+/exec")))
                return "Paste the deployed Google Apps Script web app address ending in /exec, not a Drive folder link.";
        } catch (IllegalArgumentException ex) { return "Enter a valid HTTPS upload address."; }
        if (!code(studyId) || !code(participantId) || (!taskCode.isBlank() && !code(taskCode)))
            return "Use 1–80 letters, digits, dots, underscores or hyphens for study, participant and optional task codes.";
        if (!acknowledged) return "Read the research setup notice and acknowledge it before connecting.";
        return null;
    }
    static boolean code(String value) { return value != null && value.matches("[A-Za-z0-9._-]{1,80}"); }
    static boolean validToken(String value) { return value != null && value.matches("[A-Za-z0-9_-]{32,256}"); }
    String binding() { return GazeSessionStore.projectKey(connector.name() + "\n" + endpoint + "\n" + studyId + "\n" + participantId); }
    boolean canUpload() { return enabled && problem() == null; }
}
