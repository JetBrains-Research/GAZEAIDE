package io.github.gazehighlighter;

/** The developer selects the question; gaze supplies its code context, not inferred intent. */
public enum GazeReviewPurpose {
    EXPLAIN("Explain this block", "Explain the target block's behavior and important assumptions concisely. Use only the target block; identify missing context."),
    COMPARE("Compare viewed regions", "Compare the target block with the other recently viewed regions. Check whether their assumptions and behavior agree. If fewer than two distinct regions are available, say what else must be viewed; do not invent a relationship."),
    REVIEW("Review for possible problems", "Review the captured code for concrete possible defects. For each concern, give the location, a triggering example and a way to check it. Separate supported findings from hypotheses; do not claim a complete review.");
    public final String title, instruction;
    GazeReviewPurpose(String title, String instruction) { this.title = title; this.instruction = instruction; }
    public static GazeReviewPurpose from(String value) {
        try { return valueOf(value); } catch (RuntimeException ignored) { return COMPARE; }
    }
    @Override public String toString() { return title; }
}
