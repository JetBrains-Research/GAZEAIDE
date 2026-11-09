package io.github.gazehighlighter;

import java.util.List;

/** An immutable snapshot of a finished (or in-progress) recording, ready to export. */
public final class GazeSession {
    public final String projectName;
    public final String researchRecordingId;
    public final long   startMs;
    public final long   endMs;
    public final List<GazeEvent> events;
    public final GazeSessionMetadata metadata;
    public final GazeBlinkMonitor.Data blink;
    public final List<ConfigurationChange> configurationChanges;
    public record ConfigurationChange(long elapsedMs, boolean paused,
                                      GazeSessionMetadata.InitialConfiguration configuration) {}

    public GazeSession(String projectName, long startMs, long endMs, List<GazeEvent> events) {
        this(projectName, startMs, endMs, events, null, List.of());
    }

    public GazeSession(String projectName, long startMs, long endMs, List<GazeEvent> events,
                       GazeSessionMetadata metadata, List<ConfigurationChange> changes) {
        this(projectName,startMs,endMs,events,metadata,changes,null);
    }
    public GazeSession(String projectName, long startMs, long endMs, List<GazeEvent> events,
                       GazeSessionMetadata metadata, List<ConfigurationChange> changes, GazeBlinkMonitor.Data blink) {
        this.projectName = projectName;
        this.researchRecordingId = "intellij-" + startMs;
        this.startMs     = startMs;
        this.endMs       = endMs;
        this.events      = List.copyOf(events);
        this.metadata = metadata;
        this.blink = blink;
        this.configurationChanges = List.copyOf(changes);
    }

    public long durationMs() {
        return Math.max(0, endMs - startMs);
    }
}
