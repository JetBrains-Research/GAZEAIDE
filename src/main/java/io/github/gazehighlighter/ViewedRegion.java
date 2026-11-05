package io.github.gazehighlighter;

/** Immutable, versioned source nominated by gaze or an explicitly labelled proxy. Lines are 1-based. */
public record ViewedRegion(String filePath, int firstLine, int lastLine, String label,
                           String text, long documentStamp, String source) {
    public String key() { return filePath + ":" + firstLine + ":" + lastLine + ":" + documentStamp + ":" + source; }
    public String location() { return filePath + ":" + firstLine + "–" + lastLine; }
}
