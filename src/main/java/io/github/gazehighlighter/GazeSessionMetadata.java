package io.github.gazehighlighter;

/** Labels and configuration captured at recording start, not an assertion of study quality. */
public record GazeSessionMetadata(Labels labels, InitialConfiguration initialConfiguration) {
    public record Labels(String participant, String task, String condition) {
        public Labels { participant = clean(participant); task = clean(task); condition = clean(condition); }
        private static String clean(String value) {
            String text = value == null ? "" : value.strip().replaceAll("[\\p{Cntrl}]", " ");
            return text.substring(0, Math.min(120, text.length()));
        }
        public String summary() {
            return "Participant: " + orUnlabelled(participant) + " · Task: " + orUnlabelled(task)
                    + " · Condition: " + orUnlabelled(condition);
        }
        private static String orUnlabelled(String s) { return s.isBlank() ? "unlabelled" : s; }
    }
    public record InitialConfiguration(String inputKind, String scenario, GazeFeatureProfile features,
                                       boolean calibrated, String aiProvider, String aiModel, TrackingConfiguration tracking, ReviewConfiguration review) {
        public InitialConfiguration(String inputKind, String scenario, GazeFeatureProfile features,
                                    boolean calibrated, String aiProvider, String aiModel) {
            this(inputKind, scenario, features, calibrated, aiProvider, aiModel, null, null);
        }
        public InitialConfiguration(String inputKind, String scenario, GazeFeatureProfile features,
                                    boolean calibrated, String aiProvider, String aiModel, TrackingConfiguration tracking) {
            this(inputKind, scenario, features, calibrated, aiProvider, aiModel, tracking, null);
        }
    }
    public record TrackingConfiguration(boolean showCursor, String response, int smoothingMs, int lineHoldMs,
                                        String pipelineVersion, CalibrationCheck calibrationCheck, PersonalResponse personalResponse) {
        public TrackingConfiguration(boolean showCursor, String response, int smoothingMs, int lineHoldMs,
                                     String pipelineVersion, CalibrationCheck calibrationCheck) {
            this(showCursor, response, smoothingMs, lineHoldMs, pipelineVersion, calibrationCheck, null);
        }
    }
    public record PersonalResponse(int version, long createdAtMs, String response, boolean active,
                                   double p90VerticalPx, double jitter90Px, double targetHitRate, double acquisitionMs,
                                   double standardHitRate, double standardAcquisitionMs, int rowHeight, int targets, int samples) {}
    public record ReviewConfiguration(String purpose, boolean quietOffers, int cooldownMs) {}
    public record CalibrationCheck(int version, long checkedAtMs, int displayX, int displayY, int displayWidth, int displayHeight,
                                   double a, double b, double c, double d, double e, double f,
                                   double medianErrorPx, double p90ErrorPx, double medianVerticalPx, double p90VerticalPx,
                                   double jitter90Px, int samples, int targets) {}
    public String summary() {
        return labels.summary() + " · Initial scenario: " + GazeScenario.from(initialConfiguration.scenario()).title;
    }
}
