package io.github.gazehighlighter;

/** Fixed, researcher-selected interaction filtering; these are not physiological fixation criteria. */
public enum GazeResponse {
    EXTRA_STEADY("Extra steady — more smoothing, more delay", 260, 260),
    STEADY("Steady — less jitter", 130, 160),
    BALANCED("Balanced", 85, 110),
    RESPONSIVE("Responsive — less delay", 45, 70);
    public final String title;
    final int smoothingMs, lineHoldMs;
    GazeResponse(String title, int smoothingMs, int lineHoldMs) {
        this.title = title; this.smoothingMs = smoothingMs; this.lineHoldMs = lineHoldMs;
    }
    public static GazeResponse from(String value) {
        try { return valueOf(value); } catch (RuntimeException ignored) { return STEADY; }
    }
    @Override public String toString() { return title; }
}
