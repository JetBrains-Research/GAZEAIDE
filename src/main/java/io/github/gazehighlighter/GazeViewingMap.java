package io.github.gazehighlighter;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local, offline map using exactly the same renderer and aggregation as the companion. */
final class GazeViewingMap {
    private GazeViewingMap() {}

    static String section(GazeSession session) throws IOException {
        String runtime;
        try (var stream = GazeViewingMap.class.getResourceAsStream("/gaze/viewing-map.mjs")) {
            if (stream == null) throw new IOException("Bundled viewing-map component is missing.");
            runtime = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var snapshots = new ArrayList<ViewedRegion>();
        var refs = new LinkedHashMap<ViewedRegion, Integer>();
        var events = new ArrayList<Map<String, Object>>();
        for (GazeEvent e : session.events) {
            var event = new LinkedHashMap<String, Object>();
            event.put("elapsedMs", e.elapsedMs); event.put("dwellMs", e.dwellMs);
            event.put("file", e.filePath); event.put("line", e.line); event.put("lineText", e.lineText);
            event.put("inputKind", e.inputLabel());
            if (e.region != null) {
                Integer ref = refs.get(e.region);
                if (ref == null) {ref = snapshots.size(); refs.put(e.region, ref); snapshots.add(e.region);}
                event.put("regionRef", ref);
            }
            events.add(event);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("researchRecordingId", session.researchRecordingId); data.put("startMs", session.startMs);
        data.put("durationMs", session.durationMs()); data.put("inProgress", session.inProgress);
        data.put("metadata", session.metadata); data.put("configurationChanges", session.configurationChanges);
        data.put("mapSnapshots", snapshots); data.put("events", events);
        String json = new Gson().toJson(data).replace("<", "\\u003c");
        return "<section id=\"gazeaide-viewing-map\"></section><script type=\"module\">\n" + runtime
                + "\nmountViewingMap(document.getElementById('gazeaide-viewing-map')," + json + ");\n</script>";
    }

    static Path export(GazeSession session, Path file) throws IOException {
        Files.writeString(file, "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>GAZEAIDE viewing map</title><main style=\"max-width:1400px;margin:24px auto\">"
                + section(session) + "</main>", StandardCharsets.UTF_8);
        return file;
    }
}
