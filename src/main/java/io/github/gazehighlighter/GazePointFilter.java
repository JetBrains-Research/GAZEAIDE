package io.github.gazehighlighter;

import java.awt.Point;
import java.util.ArrayDeque;

/** Three-sample median followed by a time-based low-pass. Never used to fit calibration. */
final class GazePointFilter {
    private record Sample(double x, double y, long at) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double x, y;
    private long previous = -1;
    Point update(double rawX, double rawY, long now, int smoothingMs) {
        if (!Double.isFinite(rawX) || !Double.isFinite(rawY)) { reset(); return null; }
        // Backlogged UI callbacks may share a millisecond: zero elapsed time must not snap
        // the cursor to a fresh noisy sample or artificially advance the filter.
        if (previous >= 0 && now == previous) return new Point((int) Math.round(x), (int) Math.round(y));
        if (previous >= 0 && (now < previous || now - previous > 500)) reset();
        samples.addLast(new Sample(rawX, rawY, now));
        while (samples.size() > 3 || !samples.isEmpty() && now - samples.getFirst().at > 180) samples.removeFirst();
        double[] xs = samples.stream().mapToDouble(Sample::x).sorted().toArray();
        double[] ys = samples.stream().mapToDouble(Sample::y).sorted().toArray();
        double mx = median(xs), my = median(ys);
        if (previous < 0) { x = mx; y = my; }
        else {
            double alpha = 1 - Math.exp(-(now - previous) / (double) Math.max(1, smoothingMs));
            x += alpha * (mx - x); y += alpha * (my - y);
        }
        previous = now;
        return new Point((int) Math.round(x), (int) Math.round(y));
    }
    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 0 ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2 : sorted[n / 2];
    }
    void reset() { samples.clear(); previous = -1; }
}
