package io.github.gazehighlighter;

/**
 * One recorded "visit" to a line of code — the unit of data a {@link GazeRecordingService}
 * session is made of.
 *
 * <p>Rather than logging every raw gaze/mouse sample (which for mouse mode would fire on every
 * pixel of motion), one event is recorded per line visit, at the point the gaze leaves that
 * line — mirroring {@link GazeEngine}'s own dwell accounting ({@link CoverageTracker#flush}).
 * That keeps recordings small while still capturing exactly what a heatmap/replay needs: which
 * line, for how long, whether it triggered help, and the last stable identifier estimate.
 */
public final class GazeEvent {

    /** Epoch millis when this visit ended (gaze moved to another line / left the editor). */
    public final long timestampMs;
    /** Millis since the recording started. */
    public final long elapsedMs;

    public final String filePath;   // absolute VirtualFile path, or an unsaved-document label
    public final int    line;       // 1-based, for human-readable export
    public final int    column;     // 0-based caret column within the line at the time
    public final long   dwellMs;    // total time spent on this line during this visit
    public final String lineText;   // line source captured at visit entry, stripped for export
    public final String word;       // last stable identifier estimate (nullable)
    public final boolean explained; // legacy name: context-preparation dwell in 0.7; direct-request trigger in older versions
    public final GazeInputMode source; // input source captured at visit entry
    public final boolean simulated;
    public final ViewedRegion region;
    public String inputLabel() { return simulated ? "MOUSE_SIMULATION" : source == GazeInputMode.WEBCAM ? "WEBCAM" : "MOUSE_PROXY"; }

    public GazeEvent(long timestampMs, long elapsedMs, String filePath, int line, int column,
                      long dwellMs, String lineText, String word, boolean explained,
                      GazeInputMode source) {
        this(timestampMs, elapsedMs, filePath, line, column, dwellMs, lineText, word, explained, source, false, null);
    }

    public GazeEvent(long timestampMs, long elapsedMs, String filePath, int line, int column,
                     long dwellMs, String lineText, String word, boolean explained,
                     GazeInputMode source, boolean simulated, ViewedRegion region) {
        this.timestampMs = timestampMs;
        this.elapsedMs   = elapsedMs;
        this.filePath    = filePath;
        this.line        = line;
        this.column      = column;
        this.dwellMs     = dwellMs;
        this.lineText    = lineText;
        this.word        = word;
        this.explained   = explained;
        this.source      = source;
        this.simulated   = simulated;
        this.region      = region;
    }
}
