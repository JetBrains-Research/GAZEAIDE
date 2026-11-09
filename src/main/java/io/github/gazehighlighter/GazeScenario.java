package io.github.gazehighlighter;

/** Explicit presets: choosing one does not start recording or resume processing. */
public enum GazeScenario {
    PERSONALIZATION("Personalization", "Create your personal response profile for every scenario.",
            "With a calibrated webcam, continue to Session → Review setup → Start personalization. Follow the moving ring for about 25 seconds, then save the suggested response preset. Choose another scenario to use it. This setup task creates a profile, not a review recording.",
            false, false, false, false, false),
    FOCUS("Follow my reading", "Keep the code block you look at clear; dim the rest.",
            "After starting recording, look within one method for half a second, then another. The clear block follows your viewing. Stop recording to see the viewing report.",
            true, false, false, false, false),
    COVERAGE("Highlight viewed lines", "Tint viewed lines green in this scenario only.",
            "Read code normally. A nonblank line turns green after 500 ms of accumulated viewing in this editor. Green means viewing exposure, not understanding or completed review. Switching scenarios clears the tint.",
            false, false, false, false, false),
    FADE("Fade previously viewed code", "Make previously viewed blocks less prominent; restore them when you return.",
            "After starting recording, view a block for 1.5 seconds, move to another, then return. Earlier code fades; looking back restores it. Fading shows viewing exposure, not completed review.",
            false, true, false, false, false),
    NAMES("Highlight matching names", "Show repeated variable or method names in the block you look at.",
            "After starting recording, look at a name for half a second. Identical name text in that block gets a box. These are text matches, not a program-wide reference or data-flow analysis.",
            false, false, true, false, false),
    RELATIONSHIPS("Gaze-assisted review", "Gaze gathers code context; you choose what to ask AI.",
            "Choose a question below, then start recording and read within a block for 3 seconds. Compare needs two viewed blocks within 45 seconds. The panel prepares code context and offers a question quietly. Open it when useful, then copy/paste/send in native AI Chat.",
            false, false, false, true, true),
    WALKTHROUGH("Gaze-guided review walkthrough", "Explore an overview, files and code steps. Gaze selects the step to discuss.",
            "Open the files to review, then start recording and Open review walkthrough. Look at code to select a step, inspect its snapshot, and add notes or mark it reviewed yourself. Ask JetBrains AI is optional. Back to experiment returns to recording controls.",
            false, false, false, false, false),
    BLINK_BASELINE("Personal baseline & blink activity", "Record longer-term viewing and blink activity without adapting the interface.",
            "Use a real webcam and your participant/reference group. Read normally for several minutes. Only mark a recording as a rested reference when appropriate. Three usable minute windows and ten blinks build a reference for later recordings. The display is an exploratory closure-deviation indicator, not cognitive load or a diagnosis.",
            false, false, false, false, false),
    OBSERVE("Record viewing only", "Collect a baseline without visual adaptation or AI requests.",
            "Continue to Session, then Start recording and read code normally. Stop to inspect or export the viewing path. No code highlighting, fading or AI-context preparation runs. Viewed code is not automatically considered reviewed.",
            false, false, false, false, false),
    CUSTOM("Custom features", "Choose your own feature combination below.",
            "Choose the feature switches and delays, then click Next: session to apply them. Processing stays paused until Start recording.",
            false, false, false, false, false);

    public final String title, goal, instructions;
    private final GazeFeatureProfile profile;
    GazeScenario(String title, String goal, String instructions, boolean focus, boolean fade,
                 boolean names, boolean history, boolean ai) {
        this.title = title; this.goal = goal; this.instructions = instructions;
        profile = new GazeFeatureProfile(focus, fade, names, history, ai, 500, 1500, 3000);
    }
    public GazeFeatureProfile profile() { return profile; }
    public String liveInstructions() {
        return switch (this) {
            case FOCUS -> "Look between methods. After half a second, the clear block follows your viewing; surrounding code dims.";
            case COVERAGE -> "Read code normally. Nonblank lines turn green after 500 ms of accumulated viewing. Green shows exposure, not understanding or completed review.";
            case FADE -> "View a block for 1.5 seconds, then move to another. Earlier code fades; look back to restore it.";
            case NAMES -> "Look at a name for half a second. Identical name text in that block gets a box. Matches are text-based, not resolved program references.";
            case RELATIONSHIPS -> "Read within a block for 3 seconds to prepare a question. Compare needs two viewed blocks within 45 seconds. Open an offer or preview the captured context below; send in native AI Chat when ready.";
            case WALKTHROUGH -> "Open review walkthrough below. Look at code to select a step; add your notes and review marks there. Back to experiment returns here.";
            case OBSERVE -> "Read code normally. Only viewing visits are recorded; no visual effects or AI preparation run. Stop recording above to see results.";
            case BLINK_BASELINE -> "Read normally for several minutes. Blink events and minute windows save locally. Keep the same participant and camera reference group across recordings; only rested reference recordings update the baseline.";
            case CUSTOM -> "Your selected feature switches are active. Stop recording before changing the setup. The Viewing report and exports will show the recorded visits.";
            case PERSONALIZATION -> "The guided setup task saves a response profile. Choose another scenario to record code viewing with it.";
        };
    }
    public boolean highlightsViewedLines() { return this == COVERAGE; }
    public boolean requiresAI() { return this != CUSTOM && profile.explanations(); }
    public static GazeScenario from(String id) {
        try { return valueOf(id); } catch (RuntimeException ignored) { return CUSTOM; }
    }
    @Override public String toString() { return title; }
}
