package io.github.gazehighlighter;

public enum GazeMode {
    READING("Reading"),
    EXPLAINING("AI context ready");

    public final String label;

    GazeMode(String label) {
        this.label = label;
    }
}
