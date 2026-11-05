package io.github.gazehighlighter;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.List;

@Service(Service.Level.PROJECT)
public final class GazeContextService {
    private final GazeHistory history = new GazeHistory();
    private String preparedPrompt;
    private ViewedRegion preparedRegion;
    private record Capture(String prompt, ViewedRegion region, long sessionStartMs) {}
    private Capture lastCapture;
    private final GazeAssistanceOffer offers = new GazeAssistanceOffer();
    private String pendingKey, pendingPurpose, lastInteraction = "";
    private boolean pendingEligible;
    private ViewedRegion waitingRegion;
    private long waitingMs, delayMs;
    public void capturePrompt(String prompt, ViewedRegion region, long sessionStartMs) {
        preparedPrompt = prompt; preparedRegion = region;
        lastCapture = new Capture(prompt, region, sessionStartMs);
    }
    public String preparedPrompt() { return preparedPrompt; }
    public ViewedRegion preparedRegion() { return preparedRegion; }
    void beginSession() { clear(); offers.reset(); lastCapture = null; lastInteraction = ""; }
    void prepareOffer(String key, String purpose, boolean eligible) {
        pendingKey = key; pendingPurpose = purpose; pendingEligible = eligible;
    }
    GazeAssistanceOffer.Offer offer(boolean enabled) {
        if (!enabled) return null;
        if (pendingEligible && preparedRegion != null && pendingKey != null)
            offers.prepare(pendingKey, preparedPrompt, preparedRegion, pendingPurpose, System.currentTimeMillis());
        return offers.current();
    }
    void dismissOffer() { offers.dismiss(System.currentTimeMillis()); }
    void interaction(String stage) { lastInteraction = "Last handoff action: " + stage; }
    void waiting(ViewedRegion region, long elapsedMs, long thresholdMs) {
        waitingRegion = region; waitingMs = elapsedMs; delayMs = thresholdMs;
    }
    void leave() { waitingRegion = null; }
    String status(boolean automatic) {
        if (preparedRegion != null) return "Context prepared locally: " + preparedRegion.label() + " [" + preparedRegion.source()
                + "]. Preview code or open a question below."
                + (!pendingEligible && GazeReviewPurpose.COMPARE.title.equals(pendingPurpose) ? " View another distinct block to prepare a comparison offer." : "")
                + (lastInteraction.isEmpty() ? " No AI request sent by GAZEAIDE." : "\n" + lastInteraction);
        if (!automatic) return "Automatic AI context is off in this scenario. Ask about a selected step in Review walkthrough.";
        if (waitingRegion != null) return "Preparing AI context: " + Math.min(100, waitingMs * 100 / Math.max(1, delayMs))
                + "% · " + waitingRegion.label() + ". Keep reading within this block; moving between its lines is fine.";
        return "Waiting for code viewing. Read within one block for " + String.format(java.util.Locale.ROOT, "%.1f", GazeFeatureSettings.getInstance().getState().explainAfterMs / 1000.0)
                + " seconds. Inspect AI context is available below. Nothing is sent automatically.";
    }
    GazeSessionContext.Inspection inspect(GazeSession session) {
        long selectedStart = session == null ? 0 : session.startMs;
        if (lastCapture != null && lastCapture.sessionStartMs == selectedStart)
            return new GazeSessionContext.Inspection("Prepared locally during this session [" + lastCapture.region.source() + "].\n"
                    + "Captured source snapshots; this is not a transmission or answer log. Files may have changed.\nTarget: "
                    + lastCapture.region.location(), lastCapture.prompt, lastCapture.region);
        return GazeSessionContext.reconstruct(session);
    }
    public static GazeContextService getInstance(Project project) { return project.getService(GazeContextService.class); }
    public void viewed(ViewedRegion region) { history.add(region, System.currentTimeMillis()); }
    public void invalidate(String path) {
        history.invalidate(path);
        // A related snapshot may also occur in the prompt. Clear all transient prepared context
        // on a source edit rather than hand an apparently current multi-region question to AI.
        preparedPrompt = null; preparedRegion = null; pendingEligible = false; pendingKey = null;
        offers.dismiss(System.currentTimeMillis());
    }
    public void clear() { history.clear(); preparedPrompt = null; preparedRegion = null; waitingRegion = null; pendingKey = null; pendingEligible = false; offers.dismiss(System.currentTimeMillis()); }
    public List<ViewedRegion> recent(String source) {
        return history.recent(System.currentTimeMillis(), source).stream().filter(r -> {
            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(r.filePath());
            Document doc = file == null ? null : FileDocumentManager.getInstance().getCachedDocument(file);
            return doc != null && doc.getModificationStamp() == r.documentStamp();
        }).toList();
    }
}
