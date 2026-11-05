package io.github.gazehighlighter;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compare bounded presets on guided target following, then check the frozen choice on fresh rows.
 * Acquisition includes human reaction and tracker delay; these are not physiological fixation measures. */
final class GazePersonalizationRun {
    static final int TARGET_MS = 2400;
    static final int[] TRAIN_ROWS = {1, 7, 3, 8, 2, 6}, CHECK_ROWS = {4, 9, 0, 5};
    record Sample(double x, double y, long at) {}
    record Trial(int row, double targetY, long start, List<Sample> samples) {
        Trial { samples = List.copyOf(samples); }
    }
    record Quality(double p90VerticalPx, double jitter90Px, double hitRate, double acquisitionMs, int targets, int samples) {
        double score(int height) { return 2 * (1 - hitRate) + .25 * jitter90Px / height + .2 * acquisitionMs / TARGET_MS; }
    }
    record Result(GazeResponse response, Quality validation, Quality standard) {}
    private final List<Trial> training = new ArrayList<>(), validation = new ArrayList<>();
    private final int height;
    private GazeResponse selected;
    GazePersonalizationRun(int rowHeight) { height = Math.max(1, rowHeight); }
    boolean addTrial(Trial trial) {
        var samples = trial.samples();
        if (samples.size() < 12 || samples.get(samples.size()-1).at() - samples.get(0).at() < 1000
                || samples.stream().filter(s -> s.at() - trial.start() >= 900).count() < 8) return false;
        long previous = trial.start() - 1;
        for (var s : samples) {
            if (!Double.isFinite(s.x()) || !Double.isFinite(s.y()) || s.at() <= previous || s.at() < trial.start()
                    || s.at() - trial.start() >= TARGET_MS) return false;
            previous = s.at();
        }
        if (selected == null) {
            if (trial.row() != TRAIN_ROWS[training.size()]) return false;
            training.add(trial);
            if (training.size() == TRAIN_ROWS.length) selected = java.util.Arrays.stream(GazeResponse.values())
                    .min(Comparator.comparingDouble(p -> evaluate(training, p, height).score(height))).orElse(GazeResponse.STEADY);
        } else {
            if (validation.size() >= CHECK_ROWS.length || trial.row() != CHECK_ROWS[validation.size()]) return false;
            validation.add(trial);
        }
        return true;
    }
    boolean checking() { return selected != null; }
    boolean complete() { return validation.size() == CHECK_ROWS.length; }
    int index() { return checking() ? validation.size() : training.size(); }
    int row() { return complete() ? CHECK_ROWS[CHECK_ROWS.length-1] : checking() ? CHECK_ROWS[index()] : TRAIN_ROWS[index()]; }
    GazeResponse selected() { return selected; }
    Result result() {
        if (!complete()) throw new IllegalStateException("Fresh-target check not finished");
        return new Result(selected, evaluate(validation, selected, height), evaluate(validation, GazeResponse.STEADY, height));
    }
    static Quality evaluate(List<Trial> trials, GazeResponse response, int height) {
        List<Double> errors = new ArrayList<>(), deviations = new ArrayList<>();
        double hits = 0, considered = 0, acquisition = 0;
        var filter = new GazePointFilter(); var target = new GazeLineTarget();
        long previousEnd = -1;
        for (Trial trial : trials) {
            if (previousEnd < 0 || trial.start() - previousEnd > 500) { filter.reset(); target.reset(); }
            List<Double> settledY = new ArrayList<>();
            long correctSince = -1, acquiredAt = -1;
            for (Sample s : trial.samples()) {
                Point p = filter.update(s.x(), s.y(), s.at(), response.smoothingMs);
                int row = trial.row() + (int) Math.floor((p.y - trial.targetY()) / height + .5);
                double center = trial.targetY() + (row - trial.row()) * height;
                int active = target.update(row, p.y, center, height, s.at(), response.lineHoldMs);
                long elapsed = s.at() - trial.start();
                if (active == trial.row()) {
                    if (correctSince < 0) correctSince = s.at();
                    if (acquiredAt < 0 && s.at() - correctSince >= 150) acquiredAt = correctSince - trial.start();
                } else correctSince = -1;
                if (elapsed >= 350) { considered++; if (active == trial.row()) hits++; }
                if (elapsed >= 900) { errors.add(Math.abs(p.y - trial.targetY())); settledY.add((double) p.y); }
                previousEnd = s.at();
            }
            double median = percentile(settledY, .5);
            for (double y : settledY) deviations.add(Math.abs(y - median));
            acquisition += acquiredAt < 0 ? TARGET_MS : acquiredAt;
        }
        return new Quality(percentile(errors, .9), percentile(deviations, .9), considered == 0 ? 0 : hits / considered,
                acquisition / Math.max(1, trials.size()), trials.size(), errors.size());
    }
    private static double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) return 0;
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size()-1, (int) Math.ceil(fraction * sorted.size()) - 1));
    }
}
