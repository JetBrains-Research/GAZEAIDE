package io.github.gazehighlighter;

/** One local IDE-user profile. Public fields support IntelliJ XML persistence. No raw samples retained. */
public final class GazePersonalResponseProfile {
    public int version;
    public long createdAtMs;
    public String response = GazeResponse.STEADY.name(), calibrationKey = "";
    public double p90VerticalPx, jitter90Px, targetHitRate, acquisitionMs;
    public double standardHitRate, standardAcquisitionMs;
    public int rowHeight, validationTargets, validationSamples;
    public GazePersonalResponseProfile() {}
    static GazePersonalResponseProfile create(GazePersonalizationRun.Result result, String key, int height) {
        var p = new GazePersonalResponseProfile(); p.version = 1; p.createdAtMs = System.currentTimeMillis();
        p.response = result.response().name(); p.calibrationKey = key; p.rowHeight = height;
        var q = result.validation(); p.p90VerticalPx = q.p90VerticalPx(); p.jitter90Px = q.jitter90Px();
        p.targetHitRate = q.hitRate(); p.acquisitionMs = q.acquisitionMs();
        p.standardHitRate = result.standard().hitRate(); p.standardAcquisitionMs = result.standard().acquisitionMs();
        p.validationTargets = q.targets(); p.validationSamples = q.samples(); return p;
    }
    boolean exists() {
        return version == 1 && createdAtMs > 0 && validationTargets == 4 && validationSamples >= 32 && rowHeight > 0
                && response != null && GazeResponse.from(response).name().equals(response) && calibrationKey != null && !calibrationKey.isBlank()
                && Double.isFinite(p90VerticalPx) && p90VerticalPx >= 0 && Double.isFinite(jitter90Px) && jitter90Px >= 0
                && Double.isFinite(targetHitRate) && targetHitRate >= 0 && targetHitRate <= 1
                && Double.isFinite(acquisitionMs) && acquisitionMs >= 0 && acquisitionMs <= GazePersonalizationRun.TARGET_MS;
    }
    boolean compatible(String key) { return exists() && calibrationKey.equals(key); }
    String summary() {
        return String.format(java.util.Locale.ROOT, "Personal profile: %s. Fresh-target check: %.0f%% target-row samples; p90 vertical error %.0f px (%.1f task rows). Guided-task acquisition %.0f ms. Saved %s.",
                GazeResponse.from(response), 100 * targetHitRate, p90VerticalPx, p90VerticalPx / Math.max(1, rowHeight), acquisitionMs,
                java.time.Instant.ofEpochMilli(createdAtMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate());
    }
}
