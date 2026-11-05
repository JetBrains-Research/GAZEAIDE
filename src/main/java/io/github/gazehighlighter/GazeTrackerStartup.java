package io.github.gazehighlighter;

/** Monotonic startup progress and one bounded recovery when the IDE replaces JCEF. */
final class GazeTrackerStartup {
    enum Stage {
        ASSETS(30_000, "Preparing the local webcam bridge…",
                "Could not prepare the local webcam bridge in time."),
        BROWSER(90_000, "Waiting for IntelliJ's embedded browser (JCEF)…",
                "IntelliJ's embedded browser (JCEF) did not start. Restart the IDE, then reconnect the webcam. If this repeats, check idea.log for JCEF startup errors."),
        PAGE(30_000, "Loading the local webcam bridge…",
                "The local webcam bridge did not respond. Retry the webcam connection; check idea.log if this repeats."),
        RUNTIME(120_000, "Loading the eye-tracking runtime (internet required)…",
                "The eye-tracking runtime did not load. Check internet access to cdn.jsdelivr.net and retry."),
        EYE_MODEL(120_000, "Loading the bundled gaze model…",
                "The bundled gaze model did not load. Retry the webcam connection; check idea.log if this repeats."),
        FACE_MODEL(120_000, "Loading face tracking (internet required)…",
                "Face tracking did not load. Check internet access to cdn.jsdelivr.net and storage.googleapis.com and retry."),
        CAMERA(45_000, "Opening the webcam. Allow IntelliJ camera access if macOS asks…",
                "The webcam did not open. Check macOS System Settings → Privacy & Security → Camera, close other camera apps, and retry."),
        READY(0, "Camera ready. Face the camera with your eyes visible; waiting for valid gaze samples.", "");

        final long timeoutMs;
        final String message, failure;
        Stage(long timeoutMs, String message, String failure) {
            this.timeoutMs = timeoutMs; this.message = message; this.failure = failure;
        }
    }

    private Stage stage = Stage.ASSETS;
    private long sinceMs;
    private boolean recoveredBrowser;

    GazeTrackerStartup(long nowMs) { sinceMs = nowMs; }
    Stage stage() { return stage; }
    String message() { return stage.message; }
    boolean advance(Stage next, long nowMs) {
        // Late load/lifespan callbacks must not replace newer pipeline progress or extend a deadline.
        if (next.ordinal() <= stage.ordinal()) return false;
        stage = next; sinceMs = nowMs; return true;
    }
    String timeout(long nowMs) {
        return stage.timeoutMs > 0 && nowMs - sinceMs >= stage.timeoutMs ? stage.failure : null;
    }
    boolean recoverBrowser(long nowMs) {
        if (recoveredBrowser) return false;
        recoveredBrowser = true; stage = Stage.ASSETS; sinceMs = nowMs; return true;
    }
}
