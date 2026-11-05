package io.github.gazehighlighter;

/** Immutable, credential-free feature configuration used by presets and research exports. */
public record GazeFeatureProfile(boolean focus, boolean fadeViewed, boolean relationships,
                                 boolean historyContext, boolean explanations,
                                 int stableMs, int fadeAfterMs, int explainAfterMs) {
    public static GazeFeatureProfile from(GazeFeatureSettings.Options s) {
        return new GazeFeatureProfile(s.focus, s.fadeViewed, s.relationships, s.historyContext,
                s.explanations, s.stableMs, s.fadeAfterMs, s.explainAfterMs);
    }
    public void applyTo(GazeFeatureSettings.Options s) {
        s.focus = focus; s.fadeViewed = fadeViewed; s.relationships = relationships;
        s.historyContext = historyContext; s.explanations = explanations;
        s.stableMs = Math.max(200, Math.min(2000, stableMs));
        s.fadeAfterMs = Math.max(500, Math.min(30000, fadeAfterMs));
        s.explainAfterMs = Math.max(1000, Math.min(15000, explainAfterMs));
        // Processing pause is an independent control and is deliberately preserved.
    }
}
