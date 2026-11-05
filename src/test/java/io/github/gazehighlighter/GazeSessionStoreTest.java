package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GazeSessionStoreTest {
    @TempDir Path directory;
    @Test void reloadAfterRestartPreservesSnapshotsLabelsConfigurationAndInput() throws Exception {
        var r = new ViewedRegion("/code.java", 1, 2, "Ω", "String x = \"Ω\";", 7, "MOUSE_PROXY");
        var event = new GazeEvent(11000, 1000, r.filePath(), 1, 0, 800, "code", null, false, GazeInputMode.MOUSE, false, r);
        var config = new GazeSessionMetadata.InitialConfiguration("MOUSE_PROXY", "FOCUS", GazeScenario.FOCUS.profile(), false, "", "");
        var metadata = new GazeSessionMetadata(new GazeSessionMetadata.Labels("P07", "validation", "focus"), config);
        var session = new GazeSession("demo", 10000, 14000, List.of(event), metadata, List.of(new GazeSession.ConfigurationChange(0, false, config)));
        new GazeSessionStore(directory).save(session);
        var recovered = new GazeSessionStore(directory).load().get(0);
        assertEquals(metadata, recovered.metadata); assertEquals("Ω", recovered.events.get(0).region.label());
        assertEquals("MOUSE_PROXY", recovered.events.get(0).inputLabel());
        assertEquals(1, recovered.configurationChanges.size());
    }
    @Test void damagedSessionDoesNotHideOthersAndSavingSameSessionDoesNotDuplicateIt() throws Exception {
        var store = new GazeSessionStore(directory);
        var first = new GazeSession("p", 10000, 11000, List.of());
        var second = new GazeSession("p", 20000, 21000, List.of());
        store.save(first); store.save(first); store.save(second);
        Files.writeString(directory.resolve("damaged.json"), "{broken");
        var sessions = store.load(); assertEquals(2, sessions.size()); assertEquals(20000, sessions.get(0).startMs);
        assertNotEquals(GazeSessionStore.projectKey("/a"), GazeSessionStore.projectKey("/b"));
    }
}
