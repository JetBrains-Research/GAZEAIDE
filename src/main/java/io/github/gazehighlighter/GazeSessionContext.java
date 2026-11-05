package io.github.gazehighlighter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reconstructs an inspectable prompt from the selected recording, without reading today's files. */
final class GazeSessionContext {
    record Inspection(String description, String prompt, ViewedRegion region) {
        String text() { return description + (prompt == null ? "" : "\n\n" + GazePrompt.SYSTEM + "\n\n" + prompt); }
    }

    static Inspection reconstruct(GazeSession session) {
        if (session == null) return empty("No recording selected. Start recording in Gaze-assisted review, read within a block for 3 seconds, then inspect here.");
        List<GazeEvent> events = session.events;
        int last = events.size() - 1;
        String latestSource = last < 0 ? "" : events.get(last).inputLabel();
        while (last >= 0 && events.get(last).inputLabel().equals(latestSource) && !usable(events.get(last))) last--;
        if (last < 0 || !events.get(last).inputLabel().equals(latestSource))
            return empty("This recording has no captured source regions for its latest input. During recording, return to the code editor and read code, then inspect again. Older recordings may contain line visits without region snapshots.");
        GazeEvent target = events.get(last);
        String source = target.inputLabel();
        GazeHistory history = new GazeHistory();
        Map<String, Long> versions = new HashMap<>();
        int first = last;
        // Do not cross an input switch, including a switch away and back to the same source.
        while (first > 0 && events.get(first - 1).inputLabel().equals(source)
                && target.timestampMs - events.get(first - 1).timestampMs <= 45_000) first--;
        for (int i = first; i <= last; i++) {
            GazeEvent event = events.get(i);
            if (!usable(event)) continue;
            ViewedRegion region = event.region;
            Long previous = versions.put(region.filePath(), region.documentStamp());
            if (previous != null && previous.longValue() != region.documentStamp()) history.invalidate(region.filePath());
            history.add(region, event.timestampMs);
        }
        var config = session.metadata == null ? null : session.metadata.initialConfiguration();
        for (var change : session.configurationChanges)
            if (change.elapsedMs() <= target.elapsedMs) config = change.configuration();
        boolean includeHistory = config == null || config.features() == null || config.features().historyContext();
        GazeReviewPurpose purpose = config == null || config.review() == null ? GazeReviewPurpose.COMPARE
                : GazeReviewPurpose.from(config.review().purpose());
        String prompt = GazePrompt.build(target.lineText == null ? target.region.text() : target.lineText,
                target.region.text(), target.word, includeHistory ? history.recent(target.timestampMs, source) : List.of(), purpose);
        return new Inspection("Reconstructed from the selected recording's captured source snapshots [" + source + "].\n"
                + "This is a new draft for inspection; it does not prove a prompt was prepared or sent during that session. Files may have changed.\n"
                + "Target: " + target.region.location(), prompt, target.region);
    }

    private static boolean usable(GazeEvent event) {
        return event.region != null && event.region.text() != null && !event.region.text().isBlank()
                && event.inputLabel().equals(event.region.source());
    }
    private static Inspection empty(String message) { return new Inspection(message, null, null); }
}
