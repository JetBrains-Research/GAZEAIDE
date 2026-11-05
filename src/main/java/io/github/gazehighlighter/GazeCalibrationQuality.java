package io.github.gazehighlighter;

import java.util.List;

/** Robust point summaries and independent validation, in desktop logical pixels. */
final class GazeCalibrationQuality {
    record Sample(double x, double y, long atMs) {}
    record Estimate(double x, double y, double spread90, int retained, long spanMs) {}
    record Validation(double medianErrorPx, double p90ErrorPx, double medianVerticalPx,
                      double p90VerticalPx, double jitter90Px, int samples, int targets) {
        String summary(int lineHeight) {
            return String.format(java.util.Locale.ROOT,
                    "Independent accuracy check: median error %.0f px; 90th percentile %.0f px.\n"
                    + "Vertical error: median %.0f px; 90th percentile %.0f px (about %.1f code lines at %d px/line).\n"
                    + "Within-target jitter (90th percentile): %.0f px. %d samples across %d targets.\n"
                    + "This estimates targeting under the current setup, not reading comprehension or a fixation detector.",
                    medianErrorPx, p90ErrorPx, medianVerticalPx, p90VerticalPx,
                    p90VerticalPx / Math.max(1, lineHeight), lineHeight, jitter90Px, samples, targets);
        }
    }
    static Estimate estimate(List<Sample> samples) {
        List<Sample> finite = samples.stream().filter(s -> Double.isFinite(s.x) && Double.isFinite(s.y)).toList();
        if (finite.isEmpty()) return null;
        double x = percentile(finite.stream().mapToDouble(Sample::x).toArray(), .5);
        double y = percentile(finite.stream().mapToDouble(Sample::y).toArray(), .5);
        double medianRadius = percentile(finite.stream().mapToDouble(s -> Math.hypot(s.x - x, s.y - y)).toArray(), .5);
        double limit = Math.max(12, medianRadius * 3);
        List<Sample> kept = finite.stream().filter(s -> Math.hypot(s.x - x, s.y - y) <= limit).toList();
        double mx = percentile(kept.stream().mapToDouble(Sample::x).toArray(), .5);
        double my = percentile(kept.stream().mapToDouble(Sample::y).toArray(), .5);
        double spread = percentile(kept.stream().mapToDouble(s -> Math.hypot(s.x - mx, s.y - my)).toArray(), .9);
        long span = kept.stream().mapToLong(Sample::atMs).max().orElse(0) - kept.stream().mapToLong(Sample::atMs).min().orElse(0);
        return new Estimate(mx, my, spread, kept.size(), span);
    }
    static boolean usable(Estimate e, int count, double screenDiagonal) {
        return e != null && e.retained >= 10 && e.retained >= Math.ceil(count * .7)
                && e.spanMs >= 700 && e.spread90 <= Math.max(25, screenDiagonal * .035);
    }
    static double percentile(double[] values, double fraction) {
        if (values.length == 0) return Double.NaN;
        java.util.Arrays.sort(values);
        double index = (values.length - 1) * fraction;
        int lower = (int) index, upper = (int) Math.ceil(index);
        return values[lower] + (values[upper] - values[lower]) * (index - lower);
    }
}
