package io.github.gazehighlighter;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** An explicit allowlist, independent of full local exports. No generic serialization of session objects. */
final class GazeRemotePayload {
    static final String PROTOCOL = "gazeaide-upload-v1";
    static final int MAX_BYTES = 8 * 1024 * 1024, MAX_EVENTS = 50000, MAX_CHANGES = 5000;
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static JsonObject base(GazeRemoteConnection connection, String id, String kind) {
        JsonObject out = new JsonObject(); out.addProperty("protocol", PROTOCOL); out.addProperty("schemaVersion", 1);
        out.addProperty("uploadId", id); out.addProperty("kind", kind);
        out.addProperty("studyId", connection.studyId()); out.addProperty("participantId", connection.participantId()); return out;
    }
    static JsonObject test(GazeRemoteConnection connection) { return base(connection, UUID.randomUUID().toString(), "connection_test"); }
    static JsonObject session(GazeSession session, GazeRemoteConnection connection, String id) {
        if (session.events.size() > MAX_EVENTS || session.configurationChanges.size() > MAX_CHANGES)
            throw new IllegalArgumentException("Recording exceeds this connector's event limit. Keep the local recording and revise the study upload design.");
        JsonObject out = base(connection, id, "session");
        out.addProperty("pluginVersion", "1.1.0"); out.addProperty("taskCode", connection.taskCode());
        out.addProperty("durationMs", session.durationMs());
        if (session.metadata != null) out.add("initialConfiguration", configuration(session.metadata.initialConfiguration()));
        JsonArray changes = new JsonArray();
        for (var change : session.configurationChanges) {
            JsonObject c = new JsonObject(); c.addProperty("elapsedMs", Math.max(0, change.elapsedMs()));
            c.addProperty("paused", change.paused()); c.add("configuration", configuration(change.configuration())); changes.add(c);
        }
        out.add("configurationChanges", changes);
        Map<String, String> files = new LinkedHashMap<>(), regions = new LinkedHashMap<>(); JsonArray events = new JsonArray();
        for (GazeEvent event : session.events) {
            JsonObject e = new JsonObject(); e.addProperty("elapsedMs", Math.max(0, event.elapsedMs));
            e.addProperty("fileId", files.computeIfAbsent(event.filePath, key -> "f" + (files.size() + 1)));
            e.addProperty("line", Math.max(0, event.line)); e.addProperty("column", Math.max(0, event.column));
            e.addProperty("dwellMs", Math.max(0, event.dwellMs)); e.addProperty("input", event.inputLabel());
            e.addProperty("legacyDwellTrigger", event.explained);
            if (event.region != null) {
                e.addProperty("regionId", regions.computeIfAbsent(event.region.key(), key -> "r" + (regions.size() + 1)));
                e.addProperty("regionFirstLine", Math.max(0, event.region.firstLine()));
                e.addProperty("regionLastLine", Math.max(0, event.region.lastLine()));
            }
            events.add(e);
        }
        out.add("events", events); encoded(out); return out;
    }
    private static JsonObject configuration(GazeSessionMetadata.InitialConfiguration config) {
        JsonObject out = new JsonObject(); if (config == null) return out;
        out.addProperty("input", known(config.inputKind(), "WEBCAM", "MOUSE_PROXY", "MOUSE_SIMULATION"));
        out.addProperty("scenario", known(config.scenario(), Arrays.stream(GazeScenario.values()).map(Enum::name).toArray(String[]::new)));
        out.addProperty("calibrated", config.calibrated());
        if (config.features() != null) {
            var f = config.features(); JsonObject features = new JsonObject();
            features.addProperty("focus", f.focus()); features.addProperty("fadeViewed", f.fadeViewed()); features.addProperty("relationships", f.relationships());
            features.addProperty("historyContext", f.historyContext()); features.addProperty("explanations", f.explanations());
            features.addProperty("stableMs", f.stableMs()); features.addProperty("fadeAfterMs", f.fadeAfterMs()); features.addProperty("explainAfterMs", f.explainAfterMs());
            out.add("features", features);
        }
        var tracking = config.tracking();
        if (tracking != null) {
            JsonObject t = new JsonObject(); t.addProperty("showCursor", tracking.showCursor());
            t.addProperty("response", known(tracking.response(), "RESPONSIVE", "BALANCED", "EXTRA_STEADY", "STEADY", "BYPASSED"));
            t.addProperty("smoothingMs", tracking.smoothingMs()); t.addProperty("lineHoldMs", tracking.lineHoldMs());
            t.addProperty("pipelineVersion", known(tracking.pipelineVersion(), "raw-eyemu-median3-time-lowpass-line-hold-v2", "mouse-direct-v1"));
            var check = tracking.calibrationCheck();
            if (check != null) {
                JsonObject c = new JsonObject(); c.addProperty("version", check.version());
                c.addProperty("displayWidth", check.displayWidth()); c.addProperty("displayHeight", check.displayHeight());
                number(c, "medianErrorPx", check.medianErrorPx()); number(c, "p90ErrorPx", check.p90ErrorPx());
                number(c, "medianVerticalPx", check.medianVerticalPx()); number(c, "p90VerticalPx", check.p90VerticalPx()); number(c, "jitter90Px", check.jitter90Px());
                c.addProperty("samples", check.samples()); c.addProperty("targets", check.targets()); t.add("calibration", c);
            }
            var personal = tracking.personalResponse();
            if (personal != null) {
                JsonObject p = new JsonObject(); p.addProperty("version", personal.version()); p.addProperty("active", personal.active());
                p.addProperty("response", known(personal.response(), "RESPONSIVE", "BALANCED", "EXTRA_STEADY", "STEADY"));
                number(p, "p90VerticalPx", personal.p90VerticalPx()); number(p, "jitter90Px", personal.jitter90Px());
                number(p, "targetHitRate", personal.targetHitRate()); number(p, "acquisitionMs", personal.acquisitionMs());
                number(p, "standardHitRate", personal.standardHitRate()); number(p, "standardAcquisitionMs", personal.standardAcquisitionMs());
                p.addProperty("rowHeight", personal.rowHeight()); p.addProperty("targets", personal.targets()); p.addProperty("samples", personal.samples()); t.add("personalResponse", p);
            }
            out.add("tracking", t);
        }
        if (config.review() != null) {
            JsonObject r = new JsonObject(); r.addProperty("purpose", known(config.review().purpose(), "EXPLAIN", "COMPARE", "REVIEW"));
            r.addProperty("quietOffers", config.review().quietOffers()); r.addProperty("cooldownMs", config.review().cooldownMs()); out.add("review", r);
        }
        return out;
    }
    private static void number(JsonObject o, String name, double value) { if (Double.isFinite(value)) o.addProperty(name, value); }
    private static String known(String value, String... allowed) {
        return Arrays.asList(allowed).contains(value) ? value : "UNKNOWN";
    }
    static String encoded(JsonObject payload) {
        String text = JSON.toJson(payload);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Reduced upload exceeds 8 MiB. The local recording is retained; revise the upload design for longer tasks.");
        return text;
    }
}
