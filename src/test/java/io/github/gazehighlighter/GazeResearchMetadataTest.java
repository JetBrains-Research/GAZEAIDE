package io.github.gazehighlighter;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GazeResearchMetadataTest {
    @Test void trackingSettingsRoundTripAndOlderRecordingsLeaveThemUnknown() {
        var gson = new com.google.gson.Gson();
        var tracking = new GazeSessionMetadata.TrackingConfiguration(false, "STEADY", 130, 160, "v2", null);
        var current = new GazeSessionMetadata.InitialConfiguration("WEBCAM", "OBSERVE", GazeScenario.OBSERVE.profile(),
                true, "JetBrains AI Chat", "native", tracking);
        assertEquals(current, gson.fromJson(gson.toJson(current), GazeSessionMetadata.InitialConfiguration.class));
        var old = new GazeSessionMetadata.InitialConfiguration("MOUSE_PROXY", "OBSERVE", GazeScenario.OBSERVE.profile(), false, "native", "unknown");
        String oldJson = gson.toJson(old);
        assertFalse(oldJson.contains("tracking"));
        assertNull(gson.fromJson(oldJson, GazeSessionMetadata.InitialConfiguration.class).tracking());
    }
    @TempDir Path temp;
    @Test void applyingAPresetPreservesProcessingPauseAndSetsPredictableDelays() {
        var options = new GazeFeatureSettings.Options(); options.paused = true; options.stableMs = 1700;
        GazeScenario.NAMES.profile().applyTo(options);
        assertTrue(options.paused); assertTrue(options.relationships);
        assertFalse(options.explanations); assertFalse(options.focus); assertEquals(500, options.stableMs);
    }
    @Test void baselineAndWalkthroughDoNotEnableAIOrAdaptation() {
        for (var scenario : List.of(GazeScenario.OBSERVE, GazeScenario.WALKTHROUGH)) {
            var p = scenario.profile();
            assertFalse(p.focus() || p.fadeViewed() || p.relationships() || p.explanations() || p.historyContext());
        }
        assertTrue(GazeScenario.RELATIONSHIPS.requiresAI()); assertEquals(GazeScenario.CUSTOM, GazeScenario.from("missing"));
    }
    @Test void optionalLabelsAreBoundedAndCannotInjectControlCharacters() {
        var labels = new GazeSessionMetadata.Labels(null, "  task\nnext  ", "x".repeat(200));
        assertEquals("", labels.participant()); assertEquals("task next", labels.task()); assertEquals(120, labels.condition().length());
    }
    @Test void exportsRetainLabelsInitialSettingsAndChangesWithoutProviderSecrets() throws Exception {
        var labels = new GazeSessionMetadata.Labels("P07", "<task>", "=condition");
        var initial = new GazeSessionMetadata.InitialConfiguration("WEBCAM", "RELATIONSHIPS",
                GazeScenario.RELATIONSHIPS.profile(), true, "Example provider", "example-model");
        var metadata = new GazeSessionMetadata(labels, initial);
        var event = new GazeEvent(11000, 1000, "/code.java", 1, 0, 800, "code", null, false, GazeInputMode.WEBCAM);
        var session = new GazeSession("research", 10000, 14000, List.of(event), metadata,
                List.of(new GazeSession.ConfigurationChange(0, false, initial), new GazeSession.ConfigurationChange(1500, true, initial)));
        String raw = Files.readString(GazeSessionExporter.exportJson(session, temp.resolve("research.json")));
        var json = JsonParser.parseString(raw).getAsJsonObject();
        assertEquals(3, json.get("schemaVersion").getAsInt());
        assertEquals("P07", json.getAsJsonObject("metadata").getAsJsonObject("labels").get("participant").getAsString());
        assertTrue(json.getAsJsonArray("configurationChanges").get(1).getAsJsonObject().get("paused").getAsBoolean());
        assertFalse(raw.contains("apiKey")); assertFalse(raw.contains("baseUrl"));
        String csv = Files.readString(GazeSessionExporter.exportCsv(session, temp.resolve("research.csv")));
        assertTrue(csv.contains("participant,task,condition")); assertTrue(csv.contains("'=condition"));
        String replay = Files.readString(GazeSessionExporter.exportHeatmapHtml(session, temp.resolve("research.html")));
        assertTrue(replay.contains("&lt;task&gt;")); assertFalse(replay.contains("<task>"));
        assertTrue(GazeWalkthrough.html("research", List.of(), metadata).contains("Participant: P07"));
    }
}
