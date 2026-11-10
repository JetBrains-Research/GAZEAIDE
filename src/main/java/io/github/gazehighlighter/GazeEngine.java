package io.github.gazehighlighter;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;

/** Per-editor gaze pipeline. All entry points and editor mutations run on the EDT. */
@SuppressWarnings("rawtypes")
public final class GazeEngine {
    private final Editor editor;
    private final Project project;
    private final GazePresentation presentation;
    private final Timer stableTimer;
    private final GazeRegionDwell contextDwell = new GazeRegionDwell();
    private final DocumentListener documentListener;
    private CoverageTracker coverage;
    private RangeHighlighter marker, dwellHighlight;
    private Inlay explanationInlay;
    private ExplanationRenderer renderer;
    private int lastLine = -1, lastColumn;
    private long lineEntered, regionEntered, candidateSince, tokenSince;
    private String lineText = "", candidateToken, stableToken;
    private GazeInputMode visitSource;
    private boolean simulated, triggered;
    private CodeRegion candidate, active;
    private final Map<Integer, CodeRegion> regionCache = new HashMap<>();
    private ViewedRegion lineRegion, readyRegion;
    private String readyPrompt;
    private long pendingSince;
    private long gapSince;
    private long cachedStamp = -1;

    public GazeEngine(Editor editor, Project project) {
        this.editor = editor; this.project = project;
        presentation = new GazePresentation(editor);
        stableTimer = new Timer(100, e -> stabilize());
        documentListener = new DocumentListener() {
            @Override public void beforeDocumentChange(@NotNull DocumentEvent event) {
                onExit(); presentation.reset(); regionCache.clear(); cachedStamp = -1;
                GazeContextService.getInstance(project).invalidate(filePath());
                if (coverage != null) { coverage.dispose(); coverage = null; }
            }
        };
        editor.getDocument().addDocumentListener(documentListener);
    }

    public void onGaze(int line,int col){onGaze(line,col,GazeClock.now());}
    void onGaze(int line, int col,long capturedAtMs) {
        if (editor.isDisposed() || project.isDisposed() || options().paused) return;
        Document doc = editor.getDocument();
        if (line < 0 || line >= doc.getLineCount()) { onExit(); return; }
        long now = capturedAtMs;
        if(now<lineEntered)return;
        if(gapSince>0) {
            if(now-gapSince>500 || candidate==null || !same(candidate,resolve(line))) onExit();
            else {candidateSince+=Math.max(0,now-gapSince);if(active!=null)regionEntered=now;gapSince=0;}
        }
        if (pendingSince > 0) {
            candidateSince += Math.max(0, now - pendingSince);
            if (active != null) { regionEntered = now; presentation.show(active, null); }
            pendingSince = 0;
        }
        if (coverage == null) coverage = new CoverageTracker(editor,
                GazeResearchSettings.getInstance(project).activeScenario(GazeFeatureProfile.from(options())).highlightsViewedLines());
        GazeModeService.getInstance(project).setActiveTracker(coverage);
        if (line != lastLine) {
            flushLine(now);
            coverage.onLeave(lastLine);
            clearDwell(); clearMarker();
            lastLine = line; lastColumn = col; lineEntered = now; triggered = false;
            candidateToken=stableToken=null;tokenSince=now;
            lineText = doc.getText(new TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)));
            visitSource = GazeInputSettings.getInstance().getInputMode();
            simulated = visitSource == GazeInputMode.WEBCAM && GazeInputSettings.getInstance().isMock();
            CodeRegion region = resolve(line);
            lineRegion = snapshot(region);
            if (!same(candidate, region)) { candidate = region; candidateSince = now; }
            refreshCursor();
            setMode(GazeMode.READING);
        }
        contextDwell.observe(lineRegion, now);
        lastColumn = col;
        VirtualFile targetFile = FileDocumentManager.getInstance().getFile(doc);
        GazeModeService.getInstance(project).updateTarget(coverage,
                (targetFile == null ? "Unsaved document" : targetFile.getName()) + ":" + (line + 1));
        String token = CodeRegion.identifier(lineText, col,filePath());
        if (!Objects.equals(candidateToken, token)) { candidateToken = token; tokenSince = now; }
        if (!stableTimer.isRunning()) stableTimer.start();
    }

    /** Stop line attribution while a webcam candidate settles. Brief moves within the same
     * block pause region residence instead of restarting it; they never add dwell time. */
    void onPendingGaze(int line) {
        if (editor.isDisposed() || project.isDisposed() || line < 0 || line >= editor.getDocument().getLineCount()) { onExit(); return; }
        long now = GazeClock.now();
        if (candidate == null || !same(candidate, resolve(line)) || pendingSince > 0 && now - pendingSince > 500) { onExit(); return; }
        if (pendingSince > 0) return;
        pendingSince = now; contextDwell.pause(now);
        flushLine(now); leaveRegion(now);
        if (coverage != null) coverage.onLeave(lastLine);
        lastLine = -1; lineEntered = 0; candidateToken = stableToken = null;
        stableTimer.stop(); clearDwell(); clearMarker(); presentation.clear();
        GazeModeService.getInstance(project).updateTarget(coverage, "Waiting for a stable code line");
        GazeModeService.getInstance(project).updateViewedRegion(coverage, null);
        GazeContextService.getInstance(project).leave();
    }

    private void stabilize() {
        if (lastLine < 0 || editor.isDisposed() || project.isDisposed()) { stableTimer.stop(); return; }
        if (!isForeground() || options().paused) { onExit(); return; }
        long now = GazeClock.now();
        boolean changed = false;
        if (candidate != null && !same(candidate, active) && now - candidateSince >= options().stableMs) {
            leaveRegion(now);
            active = candidate; regionEntered = now;
            GazeContextService.getInstance(project).viewed(snapshot(active));
            changed = true;
        }
        if (now - tokenSince >= options().stableMs && !Objects.equals(stableToken, candidateToken)) {
            stableToken = candidateToken; changed = true;
        }
        // Refresh recency without appending duplicate region entries.
        if (active != null && same(active, candidate)) {
            GazeContextService.getInstance(project).viewed(snapshot(active));
            GazeModeService.getInstance(project).updateViewedRegion(coverage, snapshot(active));
        } else GazeModeService.getInstance(project).updateViewedRegion(coverage, null);
        if (options().explanations && same(active, candidate) && active != null) {
            GazeContextService.getInstance(project).waiting(snapshot(active), contextDwell.elapsed(now), options().explainAfterMs);
            if (!lineText.isBlank() && contextDwell.claimReady(now, options().explainAfterMs)) onDwell();
        }
        if (changed) presentation.show(active, stableToken);
    }

    private void onDwell() {
        if (lastLine < 0 || editor.isDisposed() || project.isDisposed() || lineText.isBlank()
                || options().paused || !options().explanations || !isForeground()) return;
        Document doc = editor.getDocument();
        if (lastLine >= doc.getLineCount()) return;
        triggered = true;
        coverage.markHeavy(lastLine);
        setMode(GazeMode.EXPLAINING);
        List<ViewedRegion> history = options().historyContext
                ? GazeContextService.getInstance(project).recent(sourceLabel()) : List.of();
        GazeReviewPurpose purpose = GazeResearchSettings.getInstance(project).reviewPurpose();
        String context = resolve(lastLine).text();
        String prompt = GazePrompt.build(lineText, context, stableToken, history, purpose);
        ViewedRegion region = snapshot(resolve(lastLine));
        GazeRecordingService recording = GazeRecordingService.getInstance(project);
        GazeContextService service = GazeContextService.getInstance(project);
        service.capturePrompt(prompt, region, recording.isRecording() ? recording.getStartMs() : 0);
        boolean eligible = purpose != GazeReviewPurpose.COMPARE || java.util.stream.Stream.concat(history.stream(), java.util.stream.Stream.of(region))
                .map(ViewedRegion::key).distinct().count() >= 2;
        service.prepareOffer(purpose.name() + ":" + region.key() + ":" + prompt.hashCode(), purpose.title, eligible);
        readyPrompt = prompt; readyRegion = region;
        // Keep code geometry stable: offers live in the tool window, never insert a shifting block inlay.
        refreshCursor();
    }

    /** Visual-only toggle: leave dwell, context, scenarios and recording untouched. EDT only. */
    void refreshCursor() {
        clearMarker();
        if (!GazeInputSettings.getInstance().isShowCursor() || lastLine < 0 || editor.isDisposed()
                || lastLine >= editor.getDocument().getLineCount()) return;
        Document doc = editor.getDocument();
        marker = editor.getMarkupModel().addRangeHighlighter(doc.getLineStartOffset(lastLine),
                doc.getLineEndOffset(lastLine), HighlighterLayer.SELECTION, new TextAttributes(), HighlighterTargetArea.LINES_IN_RANGE);
        if (readyPrompt == null) { marker.setGutterIconRenderer(new GutterCircleRenderer()); return; }
        String prompt = readyPrompt; ViewedRegion region = readyRegion;
        marker.setGutterIconRenderer(new GutterCircleRenderer() {
            @Override public boolean isNavigateAction() { return true; }
            @Override public String getTooltipText() { return "Ask JetBrains AI about this captured code…"; }
            @Override public com.intellij.openapi.actionSystem.AnAction getClickAction() {
                return new com.intellij.openapi.project.DumbAwareAction() {
                    @Override public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                        JetBrainsAiBridge.showRequest(project, GazePrompt.SYSTEM + "\n\n" + prompt, region);
                    }
                };
            }
            @Override public boolean equals(Object other) { return this == other; }
            @Override public int hashCode() { return System.identityHashCode(this); }
        });
    }

    /** Preserve region residence for at most 500 ms of unavailable tracking. No gap dwell is added. */
    void trackingGap() {
        if(gapSince>0) return;
        long now=GazeClock.now();gapSince=now;contextDwell.pause(now);
        flushLine(now);leaveRegion(now);
        if(coverage!=null)coverage.onLeave(lastLine);
        lastLine=-1;lineEntered=0;candidateToken=stableToken=null;
        stableTimer.stop();clearDwell();clearMarker();presentation.clear();
    }

    public void onExit() {
        long now = GazeClock.now();
        flushLine(now); leaveRegion(now);
        if (coverage != null) coverage.onLeave(lastLine);
        if (!project.isDisposed()) GazeModeService.getInstance(project).updateTarget(coverage, "No active code target");
        if (!project.isDisposed()) GazeModeService.getInstance(project).updateViewedRegion(coverage, null);
        contextDwell.reset();
        if (!project.isDisposed()) GazeContextService.getInstance(project).leave();
        lastLine = -1; lineEntered = 0; candidate = active = null; pendingSince = gapSince = 0;
        candidateToken = stableToken = null; lineRegion = null;
        stableTimer.stop(); clearDwell(); clearMarker(); presentation.clear();
        setMode(GazeMode.READING);
    }

    void suspend(boolean clearHistory) {
        onExit();
        if (clearHistory) {
            presentation.reset();
            if (coverage != null) { coverage.dispose(); coverage = null; }
            if (!project.isDisposed()) GazeModeService.getInstance(project).setActiveTracker(null);
        }
    }

    /** Persist the open visit without changing dwell or splitting its live interval. */
    void checkpointVisit() {
        if(lastLine<0||lineEntered==0||project.isDisposed())return;
        long now=GazeClock.now();var rec=GazeRecordingService.getInstance(project);
        long duration=Math.max(0,now-Math.max(lineEntered,rec.getStartMs()));
        if(rec.isRecording()&&duration>0)rec.checkpointActive(new GazeEvent(now,now-rec.getStartMs(),filePath(),lastLine+1,lastColumn,duration,lineText.strip(),stableToken,triggered,visitSource,simulated,lineRegion));
    }
    /** Split visits at start/stop so intervals cannot leak across recording boundaries. */
    void recordingBoundary() {
        long now = GazeClock.now(); flushLine(now);
        contextDwell.reset();
        if (lastLine >= 0) { lineEntered = now; triggered = false; contextDwell.observe(lineRegion, now); }
    }

    private void flushLine(long now) {
        if (lastLine < 0 || lineEntered == 0) return;
        long duration = Math.max(0, now - lineEntered);
        if (coverage != null) coverage.flush(lastLine, duration);
        GazeRecordingService rec = GazeRecordingService.getInstance(project);
        if (rec.isRecording() && duration > 0) {
            long recordedStart = Math.max(lineEntered, rec.getStartMs());
            duration = Math.max(0, now - recordedStart);
            if (duration > 0) rec.record(new GazeEvent(now, now - rec.getStartMs(), filePath(), lastLine + 1,
                    lastColumn, duration, lineText.strip(), stableToken, triggered, visitSource, simulated, lineRegion));
        }
        lineEntered = 0;
    }

    private void leaveRegion(long now) {
        if (active != null && regionEntered > 0) presentation.leave(active, now - regionEntered);
        regionEntered = 0;
    }

    private CodeRegion resolve(int line) {
        Document doc = editor.getDocument();
        if (cachedStamp != doc.getModificationStamp()) { regionCache.clear(); cachedStamp = doc.getModificationStamp(); }
        CodeRegion cachedRegion = regionCache.get(line);
        if (cachedRegion != null) return cachedRegion;
        if (doc.getTextLength() <= 200000) cachedRegion = CodeRegion.find(doc.getText(), line,filePath());
        else {
            int from = Math.max(0, line - 40), to = Math.min(doc.getLineCount() - 1, line + 40);
            String text = doc.getText(new TextRange(doc.getLineStartOffset(from), doc.getLineEndOffset(to)));
            CodeRegion local = CodeRegion.find(text, line - from,filePath());
            cachedRegion = new CodeRegion(local.firstLine() + from, local.lastLine() + from, local.label(), local.text());
        }
        regionCache.put(line, cachedRegion);
        return cachedRegion;
    }
    private ViewedRegion snapshot(CodeRegion region) {
        return new ViewedRegion(filePath(), region.firstLine() + 1, region.lastLine() + 1, region.label(),
                region.text(), editor.getDocument().getModificationStamp(), sourceLabel());
    }
    private String filePath() {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file == null ? "(unsaved document)" : file.getPath();
    }
    private String sourceLabel() { return simulated ? "MOUSE_SIMULATION" : visitSource == GazeInputMode.WEBCAM ? "WEBCAM" : "MOUSE_PROXY"; }
    private static boolean same(CodeRegion a, CodeRegion b) { return a == b || a != null && b != null && a.firstLine() == b.firstLine() && a.lastLine() == b.lastLine(); }
    private boolean isForeground() {
        Window owner = SwingUtilities.getWindowAncestor(editor.getContentComponent());
        return editor.getContentComponent().isShowing() && owner != null && owner.isActive();
    }
    private static GazeFeatureSettings.Options options() { return GazeFeatureSettings.getInstance().getState(); }
    private void setMode(GazeMode mode) { if (!project.isDisposed()) GazeModeService.getInstance(project).setMode(mode); }
    private void clearMarker() { if (marker != null && marker.isValid()) marker.dispose(); marker = null; }
    private void clearDwell() {
        if (dwellHighlight != null && dwellHighlight.isValid()) dwellHighlight.dispose();
        if (explanationInlay != null && explanationInlay.isValid()) explanationInlay.dispose();
        dwellHighlight = null; explanationInlay = null; renderer = null; readyPrompt = null; readyRegion = null;
    }
    public void dispose() {
        onExit(); presentation.reset();
        editor.getDocument().removeDocumentListener(documentListener);
        if (coverage != null) coverage.dispose();
        if (!project.isDisposed()) GazeModeService.getInstance(project).clearActiveTracker(coverage);
    }
}
