package io.github.gazehighlighter;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/** Timed calibration protocol. Uses only raw samples; never changes persistent settings. */
final class GazeCalibrationRun {
    static final int SETTLE_MS = 900, COLLECT_MS = 1800, MAX_COLLECT_MS = 6000;
    static final double[][] TRAIN = {{.08,.08},{.5,.08},{.92,.08},{.08,.5},{.5,.5},
            {.92,.5},{.08,.92},{.5,.92},{.92,.92}};
    static final double[][] CHECK = {{.25,.25},{.75,.25},{.25,.75},{.75,.75},{.5,.35}};
    enum Phase { SETTLE, COLLECT, PAUSED, DONE, FAILED }
    private final Rectangle bounds;
    private final List<GazeCalibration.Sample> training = new ArrayList<>();
    private final List<GazeCalibrationQuality.Sample> current = new ArrayList<>();
    private final List<Double> errors = new ArrayList<>(), vertical = new ArrayList<>(), jitter = new ArrayList<>();
    private boolean checking;
    private int index, retries;
    private long started;
    private Phase phase = Phase.SETTLE;
    private String message = "Look at the dot. Keep your head comfortably still.";
    private GazeCalibration calibration;

    GazeCalibrationRun(Rectangle bounds, GazeCalibration existing, long now) {
        this.bounds = new Rectangle(bounds); checking = existing != null; calibration = existing; started = now;
    }
    Phase phase() { return phase; }
    boolean checking() { return checking; }
    int index() { return index; }
    int count() { return checking ? CHECK.length : TRAIN.length; }
    double[] target() { return (checking ? CHECK : TRAIN)[Math.min(index, count() - 1)]; }
    String message() { return message; }
    GazeCalibration calibration() { return calibration; }
    double progress(long now) { return phase == Phase.PAUSED ? 0 : Math.min(1, (now - started) / (double) (phase == Phase.SETTLE ? SETTLE_MS : COLLECT_MS)); }
    void retryTarget(long now) {
        if (phase != Phase.PAUSED) return;
        retries = 0; current.clear(); phase = Phase.SETTLE; started = now;
        message = "Retrying this target. Completed targets are retained. Look at the dot.";
    }
    void sample(double x, double y, long now) {
        if (phase == Phase.COLLECT && now >= started && Double.isFinite(x) && Double.isFinite(y))
            current.add(new GazeCalibrationQuality.Sample(x, y, now));
    }
    void tick(long now) {
        if (phase == Phase.SETTLE && now - started >= SETTLE_MS) {
            current.clear(); phase = Phase.COLLECT; started = now;
            message = "Keep looking at the dot. Blink normally.";
        } else if (phase == Phase.COLLECT && now - started >= COLLECT_MS) {
            var estimate = GazeCalibrationQuality.estimate(current);
            boolean enough = estimate != null && current.size() >= 10
                    && current.get(current.size() - 1).atMs() - current.get(0).atMs() >= 700;
            // Training rejects unstable captures. Validation retains all finite samples so poor
            // accuracy/jitter cannot be hidden by discarding inconvenient validation points.
            boolean usable = checking ? enough : GazeCalibrationQuality.usable(estimate, current.size(), Math.hypot(bounds.width, bounds.height));
            if (!usable && now - started < MAX_COLLECT_MS) {
                message = "Collecting longer: need enough steady, valid samples."; return;
            }
            if (!usable) {
                if (++retries <= 2) {
                    phase = Phase.SETTLE; started = now; current.clear();
                    message = "Retry " + retries + "/2: insufficient or unstable samples. Check lighting and face framing.";
                } else {
                    current.clear(); phase = Phase.PAUSED;
                    message = "Paused: not enough steady samples. Improve lighting and face framing, then click Retry this target. Completed targets are retained; you can retry as often as needed.";
                }
                return;
            }
            double tx = bounds.x + target()[0] * bounds.width, ty = bounds.y + target()[1] * bounds.height;
            if (checking) {
                double[] xs = current.stream().mapToDouble(s -> calibration.x(s.x(), s.y())).toArray();
                double[] ys = current.stream().mapToDouble(s -> calibration.y(s.x(), s.y())).toArray();
                double mx = GazeCalibrationQuality.percentile(xs.clone(), .5), my = GazeCalibrationQuality.percentile(ys.clone(), .5);
                for (int i = 0; i < xs.length; i++) {
                    errors.add(Math.hypot(xs[i] - tx, ys[i] - ty)); vertical.add(Math.abs(ys[i] - ty));
                    jitter.add(Math.hypot(xs[i] - mx, ys[i] - my));
                }
            } else training.add(new GazeCalibration.Sample(estimate.x(), estimate.y(), tx, ty));
            retries = 0; index++; current.clear();
            if (index == count()) {
                if (checking) { phase = Phase.DONE; return; }
                calibration = GazeCalibration.fit(training);
                if (calibration == null) { fail("The nine targets did not produce a usable correction. Try again with clearer face framing."); return; }
                checking = true; index = 0;
                message = "Correction fitted. Now check accuracy at five new targets.";
            } else message = checking ? "Accuracy check: look at the next dot." : "Look at the next dot.";
            phase = Phase.SETTLE; started = now;
        }
    }
    private void fail(String text) { phase = Phase.FAILED; message = text; }
    GazeCalibrationQuality.Validation validation() {
        if (phase != Phase.DONE) return null;
        return new GazeCalibrationQuality.Validation(percentile(errors, .5), percentile(errors, .9),
                percentile(vertical, .5), percentile(vertical, .9), percentile(jitter, .9), errors.size(), CHECK.length);
    }
    private static double percentile(List<Double> values, double p) {
        return GazeCalibrationQuality.percentile(values.stream().mapToDouble(Double::doubleValue).toArray(), p);
    }
}
