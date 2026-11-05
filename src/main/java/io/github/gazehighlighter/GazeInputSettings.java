package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level settings for the gaze <i>input source</i>.
 *
 * <p>Chooses between mouse-as-proxy ({@link GazeInputMode#MOUSE}, the default) and the UnitEye
 * webcam eye tracker ({@link GazeInputMode#WEBCAM}, run via an embedded browser).
 *
 * <p>This service selects where gaze comes from. Code explanations use the installed
 * JetBrains AI Chat through {@link JetBrainsAiBridge}.
 */
@State(name = "ImplicitGazeInput", storages = @Storage("gaze-input.xml"))
public final class GazeInputSettings implements PersistentStateComponent<GazeInputSettings.State> {

    /** Public mutable fields so xmlb can (de)serialize them. */
    public static final class State {
        /** Stored as the enum name; read back leniently via {@link GazeInputMode#from}. */
        public String inputMode = GazeInputMode.MOUSE.name();
        /** Verify the webcam-mode plumbing with the mouse instead of a real camera (debug aid). */
        public boolean mock = false;
        public boolean showCursor = false;
        public String response = GazeResponse.STEADY.name();
        public boolean usePersonalResponse;
        public GazePersonalResponseProfile personalResponse = new GazePersonalResponseProfile();

        // ── Webcam calibration (affine correction: raw screen px -> corrected screen px) ──
        // corrected.x = calA * raw.x + calB * raw.y + calC
        // corrected.y = calD * raw.x + calE * raw.y + calF
        // Defaults to the identity transform (no correction) until the user calibrates.
        public boolean calibrated = false;
        public double calA = 1, calB = 0, calC = 0;
        public double calD = 0, calE = 1, calF = 0;
        public int calibrationVersion, displayX, displayY, displayWidth, displayHeight;
        public long checkedAtMs;
        public double medianErrorPx, p90ErrorPx, medianVerticalPx, p90VerticalPx, jitter90Px;
        public int validationSamples, validationTargets;
    }

    private State state = new State();

    public static GazeInputSettings getInstance() {
        return ApplicationManager.getApplication().getService(GazeInputSettings.class);
    }

    // ── PersistentStateComponent ────────────────────────────────────────────────

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        XmlSerializerUtil.copyBean(loaded, state);
    }

    // ── Accessors ────────────────────────────────────────────────────────────────

    public GazeInputMode getInputMode() {
        return GazeInputMode.from(state.inputMode);
    }

    public void setInputMode(GazeInputMode mode) {
        state.inputMode = mode.name();
    }

    public boolean isMock() {
        return state.mock;
    }

    public void setMock(boolean mock) {
        state.mock = mock;
    }

    // ── Webcam calibration ──────────────────────────────────────────────────────

    public boolean isCalibrated() {
        return state.calibrated && state.calibrationVersion == 2 && matchesDisplay();
    }

    public boolean isShowCursor() { return state.showCursor; }
    public void setShowCursor(boolean value) { state.showCursor = value; }
    public GazeResponse getResponse() { return isPersonalResponseActive() ? GazeResponse.from(personalResponse().response) : getStandardResponse(); }
    public GazeResponse getStandardResponse() { return GazeResponse.from(state.response); }
    public void setResponse(GazeResponse value) { state.response = value.name(); }
    public GazePersonalResponseProfile personalResponse() {
        if (state.personalResponse == null) state.personalResponse = new GazePersonalResponseProfile();
        return state.personalResponse;
    }
    public boolean hasPersonalResponse() { return personalResponse().exists(); }
    public boolean isPersonalResponseActive() { return state.usePersonalResponse && isCalibrated() && personalResponse().compatible(calibrationKey()); }
    public void setUsePersonalResponse(boolean use) { state.usePersonalResponse = use && hasPersonalResponse(); }
    public void savePersonalResponse(GazePersonalResponseProfile profile) { state.personalResponse = profile; state.usePersonalResponse = true; }
    public void clearPersonalResponse() { state.personalResponse = new GazePersonalResponseProfile(); state.usePersonalResponse = false; }
    public String personalResponseSummary() {
        if (!hasPersonalResponse()) return "No personal profile yet. Choose the Personalization scenario to create one, then use it in any scenario.";
        return (isPersonalResponseActive() ? "Active in all scenarios. " : "Using standard response settings. ") + personalResponse().summary()
                + (!isCalibrated() || !personalResponse().compatible(calibrationKey()) ? " Calibration/display changed: rerun Personalization before reuse." : "")
                + " Recheck after moving the camera or changing seating/lighting.";
    }
    String calibrationKey() {
        return state.calibrationVersion + ":" + state.displayX + ":" + state.displayY + ":" + state.displayWidth + ":" + state.displayHeight
                + ":" + state.calA + ":" + state.calB + ":" + state.calC + ":" + state.calD + ":" + state.calE + ":" + state.calF;
    }
    private boolean matchesDisplay() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return true;
        var b = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        return b.x == state.displayX && b.y == state.displayY && b.width == state.displayWidth && b.height == state.displayHeight;
    }
    public String calibrationSummary() {
        if (state.calibrated && state.calibrationVersion != 2) return "Recalibrate: the raw-sample pipeline has changed since your previous calibration.";
        if (state.calibrated && !matchesDisplay()) return "Display layout changed. Recalibrate on the primary display.";
        if (!isCalibrated()) return "Not calibrated. Calibrate on the primary display before a study.";
        return String.format(java.util.Locale.ROOT, "Accuracy check: vertical error median %.0f px; 90th percentile %.0f px. Checked %s.",
                state.medianVerticalPx, state.p90VerticalPx,
                java.time.Instant.ofEpochMilli(state.checkedAtMs).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm")));
    }
    public void setCalibrationQuality(GazeCalibrationQuality.Validation q, java.awt.Rectangle display) {
        state.calibrationVersion = 2; state.checkedAtMs = System.currentTimeMillis();
        state.displayX = display.x; state.displayY = display.y; state.displayWidth = display.width; state.displayHeight = display.height;
        state.medianErrorPx = q.medianErrorPx(); state.p90ErrorPx = q.p90ErrorPx();
        state.medianVerticalPx = q.medianVerticalPx(); state.p90VerticalPx = q.p90VerticalPx(); state.jitter90Px = q.jitter90Px();
        state.validationSamples = q.samples(); state.validationTargets = q.targets();
    }

    public GazeCalibration getCalibration() {
        if (!isCalibrated()) return GazeCalibration.IDENTITY;
        return new GazeCalibration(state.calA, state.calB, state.calC,
                                    state.calD, state.calE, state.calF);
    }

    public void setCalibration(GazeCalibration cal) {
        state.calibrated = true;
        state.calA = cal.a; state.calB = cal.b; state.calC = cal.c;
        state.calD = cal.d; state.calE = cal.e; state.calF = cal.f;
    }

    public void clearCalibration() {
        state.calibrated = false;
        state.calibrationVersion = 0; state.checkedAtMs = 0;
        state.calA = 1; state.calB = 0; state.calC = 0;
        state.calD = 0; state.calE = 1; state.calF = 0;
    }
}
