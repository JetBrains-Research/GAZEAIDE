package io.github.gazehighlighter;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import javax.swing.*;
import java.awt.*;

/** In-product introduction shared by every wizard step; no prior project knowledge required. */
final class GazeHelpDialog extends DialogWrapper {
    GazeHelpDialog(Project project) { super(project); setTitle("Getting started with Gaze Research"); setOKButtonText("Got it"); init(); }
    static String guide() {
        StringBuilder text = new StringBuilder("Gaze Research uses where you look to change how code is shown or gather code for an AI question. You can try it with your mouse before setting up a webcam. It never sends AI questions automatically.\n\n"
                + "FIRST TRY — NO CAMERA OR AI ACCOUNT NEEDED\n"
                + "1. Input: choose Mouse proxy (your pointer stands in for gaze).\n"
                + "2. Scenario: choose Follow my reading and Open example code.\n"
                + "3. Session: keep New session. Labels are optional.\n"
                + "4. Review setup → Start recording. Move the pointer between methods. The viewed block stays clear while the rest dims.\n"
                + "5. Stop recording. Open Viewing report or Replay viewing trace.\n\n"
                + "WHAT THE WORDS MEAN\n"
                + "Scenario: what the plugin does with viewing input.\n"
                + "Session: one recording for a task; saved results can be reopened.\n"
                + "Calibration: align webcam gaze estimates with screen positions. Check accuracy measures remaining error without fitting a new correction.\n"
                + "Personalization: optional target-following task that selects a stability/response preset. One saved profile works with all scenarios; it stays fixed during recording. It does not learn your reading speed or understanding.\n"
                + "AI context: captured code and, when needed, recent viewed blocks included in a question. It is not an AI answer.\n"
                + "Review walkthrough: overview → files → code snapshots, with your own notes and review marks.\n"
                + "Viewing trace: the chronological path of accepted code-line visits, including returns.\n\n"
                + "CHOOSE A TASK\n");
        for (var scenario : GazeScenario.values()) text.append(scenario.title).append(" — ").append(scenario.goal).append('\n');
        return text.append("\nUSING JETBRAINS AI\nEnable/sign in to JetBrains AI Assistant or Junie in IntelliJ. Choose Gaze-assisted review, then Explain this block, Compare viewed regions, or Review for possible problems. After Start recording, viewing a block for 3 seconds prepares a question locally. Compare needs two distinct viewed blocks within 45 seconds. Quiet offers stay in the panel, at most once per 30 seconds; Open offered question previews the captured snapshot. Preview captured AI context only inspects it. Copy prompt & open AI Chat copies the prompt and opens native chat; paste and send there. Choose the assistant/model there. Replies stay in chat; GAZEAIDE cannot observe sending or answers.\n\n"
                + "FOR A RESEARCH SESSION\nUse a participant code and task/condition labels. Record viewing only is the baseline. Use the same input and response settings when comparing scenarios; choose whether the saved personal profile is active before recording. Recording locks setup, while Pause/Resume and Show gaze cursor remain available. Stop camera also pauses recording; Stop recording finishes it.\n"
                + "Recordings and source snapshots are saved locally. JSON includes input type, scenario, response/profile measurements, question purpose and configuration changes. Export CSV, JSON and an HTML report after stopping. Webcam, mouse proxy and mouse simulation remain labelled separately. Camera video and raw gaze samples are not recorded. Visits indicate estimated viewing exposure, not physiological fixations, understanding or completed review. Webcam line accuracy must be checked on the actual setup.\n\n"
                + "OPTIONAL REMOTE STUDY UPLOAD\nIn Session or Results, choose Configure remote upload. Nothing is sent until you configure and enable a receiver. Choose a research HTTPS server or a researcher-owned Google Drive Apps Script receiver; a Drive folder link alone is not a connection. Data & setup explains deployment and the reduced upload schema. Save the connection, send a synthetic test, preview the selected recording and upload manually, or opt into automatic upload after future recordings. Failed uploads remain local for explicit retry. Source code, paths, local free-text labels, notes and AI content are excluded. Disconnect stops new sends; deleting cloud data needs the study's separate withdrawal process.\n"
                + GazeRemoteUploadPanel.NOTICE).toString();
    }
    @Override protected JComponent createCenterPanel() {
        JTextArea text = new JTextArea(guide(), 24, 64); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setCaretPosition(0);
        JScrollPane pane = new JScrollPane(text); pane.setPreferredSize(new Dimension(690, 540)); return pane;
    }
}
