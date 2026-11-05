package io.github.gazehighlighter;

/** Debounces code-line targets and adds a small boundary hysteresis. Not a fixation detector. */
final class GazeLineTarget {
    private int active = -1, candidate = -1;
    private double activeCenter;
    private long since, previous = -1;
    int update(int line, double y, double center, int lineHeight, long now, int holdMs) {
        if (previous >= 0 && (now < previous || now - previous > 500)) reset();
        previous = now;
        if (active >= 0 && Math.abs(y - activeCenter) <= Math.max(1, lineHeight) * 0.68) {
            candidate = -1;
            return active;
        }
        if (line != candidate) { candidate = line; since = now; }
        if (now - since < holdMs) return -1;
        active = line; activeCenter = center; candidate = -1;
        return active;
    }
    void reset() { active = candidate = -1; previous = -1; }
}
