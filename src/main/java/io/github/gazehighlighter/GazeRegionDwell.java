package io.github.gazehighlighter;

/** Continuous region residence, independent of individual line visits. */
final class GazeRegionDwell {
    private String key;
    private long since;
    private long pausedAt = -1;
    private boolean prepared;

    void observe(ViewedRegion region, long now) {
        if (region == null) { reset(); return; }
        if (!region.key().equals(key)) {
            key = region.key(); since = now; prepared = false; pausedAt = -1;
        } else if (pausedAt >= 0) {
            since += Math.max(0, now - pausedAt); pausedAt = -1;
        }
    }
    void pause(long now) { if (key != null && pausedAt < 0) pausedAt = now; }
    long elapsed(long now) { return key == null ? 0 : Math.max(0, (pausedAt >= 0 ? pausedAt : now) - since); }
    boolean claimReady(long now, long delay) {
        if (key == null || pausedAt >= 0 || prepared || elapsed(now) < delay) return false;
        prepared = true;
        return true;
    }
    void reset() { key = null; since = 0; prepared = false; pausedAt = -1; }
}
