package io.github.gazehighlighter;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WalkthroughExportTest {
    @TempDir Path temp;
    private GazeEvent visit(ViewedRegion r, long end, long duration) {
        return new GazeEvent(10000 + end, end, r.filePath(), r.firstLine(), 0, duration,
                "code", null, false, r.source().equals("MOUSE_PROXY") ? GazeInputMode.MOUSE : GazeInputMode.WEBCAM,
                r.source().equals("MOUSE_SIMULATION"), r);
    }
    private GazeSession session(GazeEvent... events) { return new GazeSession("demo", 10000, 20000, List.of(events)); }
    @Test void groupsShortAdjacentLineVisitsBeforeFilteringNoise() {
        var a = GazeHistoryTest.region("a", 1, "WEBCAM");
        var route = GazeWalkthrough.build(session(visit(a, 200, 200), visit(a, 450, 250), visit(a, 700, 250)), 500);
        assertEquals(1, route.size()); assertEquals(700, route.get(0).dwellMs);
    }
    @Test void preservesRevisitsAndSeparatesSourceAndVersionChanges() {
        var a = GazeHistoryTest.region("a", 1, "WEBCAM"); var b = GazeHistoryTest.region("b", 1, "WEBCAM");
        var changed = GazeHistoryTest.region("a", 2, "WEBCAM"); var proxy = GazeHistoryTest.region("a", 2, "MOUSE_PROXY");
        var route = GazeWalkthrough.build(session(visit(a, 1000, 800), visit(b, 2000, 800), visit(a, 3000, 800),
                visit(changed, 4000, 800), visit(proxy, 5000, 800)), 500);
        assertEquals(5, route.size());
    }
    @Test void doesNotMergeAcrossAnUnobservedGap() {
        var a = GazeHistoryTest.region("a", 1, "WEBCAM");
        assertEquals(2, GazeWalkthrough.build(session(visit(a, 1000, 800), visit(a, 5000, 800)), 500).size());
    }
    @Test void shortGapsDoNotAccumulateIntoFalseRouteBreaksOrExposure() {
        var a = GazeHistoryTest.region("a", 1, "WEBCAM");
        var route = GazeWalkthrough.build(session(visit(a, 1000, 800), visit(a, 2500, 800),
                visit(a, 4000, 800), visit(a, 5500, 800)), 500);
        assertEquals(1, route.size()); assertEquals(3200, route.get(0).dwellMs);
    }
    @Test void csvPreservesQuotingAndNeutralizesSpreadsheetFormulas() {
        assertEquals("'  =SUM(1)", GazeSessionExporter.csv("  =SUM(1)"));
        assertEquals("'+value", GazeSessionExporter.csv("+value"));
        assertEquals("'@code", GazeSessionExporter.csv("@code"));
        assertEquals("'\tcode", GazeSessionExporter.csv("\tcode"));
        assertEquals("\"say \"\"hi\"\", Ω\"", GazeSessionExporter.csv("say \"hi\", Ω"));
        assertEquals("normalCode", GazeSessionExporter.csv("normalCode"));
    }
    @Test void exportsAnnotationsAndCodeAsTextNotExecutableMarkup() {
        var r = new ViewedRegion("/a<script>.java", 1, 2, "<img src=x>", "</code><script>alert(1)</script>", 1, "WEBCAM");
        var route = GazeWalkthrough.build(session(visit(r, 1000, 800)), 500);
        route.get(0).note = "<script>bad</script> & reviewed";
        String html = GazeWalkthrough.html("<project>", route);
        assertFalse(html.contains("<script>")); assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("WEBCAM")); assertTrue(html.contains("Version: 1"));
    }
    @Test void jsonRetainsSimulationProvenanceVersionAndUnicodeCode() throws Exception {
        var r = new ViewedRegion("/code.java", 2, 5, "résultat", "String x = \"Ω\";", 12, "MOUSE_SIMULATION");
        Path file = GazeSessionExporter.exportJson(session(visit(r, 1000, 800)), temp.resolve("session.json"));
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(4, json.get("schemaVersion").getAsInt());
        var event = json.getAsJsonArray("events").get(0).getAsJsonObject();
        assertTrue(event.get("simulated").getAsBoolean()); assertEquals("MOUSE_SIMULATION", event.get("inputKind").getAsString());
        assertEquals(12, event.getAsJsonObject("region").get("documentStamp").getAsLong());
        assertTrue(event.getAsJsonObject("region").get("text").getAsString().contains("Ω"));
    }
    @Test void heatmapSeparatesCodeVersionsAndInputSources() throws Exception {
        var a = GazeHistoryTest.region("same", 1, "WEBCAM"); var b = GazeHistoryTest.region("same", 2, "MOUSE_PROXY");
        String html = Files.readString(GazeSessionExporter.exportHeatmapHtml(session(visit(a, 1000, 800), visit(b, 2000, 800)), temp.resolve("replay.html")));
        assertTrue(html.contains("version 1, WEBCAM")); assertTrue(html.contains("version 2, MOUSE_PROXY"));
    }
}
