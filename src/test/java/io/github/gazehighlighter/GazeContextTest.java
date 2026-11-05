package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GazeContextTest {
    @TempDir Path directory;
    private static ViewedRegion region(String name, long version, String input) {
        return GazeHistoryTest.region(name, version, input);
    }
    private static GazeEvent event(ViewedRegion region, long at) {
        return new GazeEvent(at, at - 1000, region.filePath(), 2, 0, 300, region.label() + "();", null, false,
                region.source().equals("WEBCAM") ? GazeInputMode.WEBCAM : GazeInputMode.MOUSE, false, region);
    }
    private static GazeSession session(long start, List<GazeEvent> events) {
        return new GazeSession("demo", start, 60000, events);
    }

    @Test void movingBetweenLinesOfOneRegionDoesNotRestartContextPreparation() {
        var dwell = new GazeRegionDwell();
        var block = region("caller", 1, "WEBCAM");
        // Ten successive 300 ms line visits: none individually reaches 3 seconds.
        for (int i = 0; i < 10; i++) {
            dwell.observe(block, 1000 + i * 300);
            assertFalse(dwell.claimReady(1000 + i * 300, 3000));
        }
        assertTrue(dwell.claimReady(4000, 3000));
        assertFalse(dwell.claimReady(7000, 3000), "Prepare once per continuous region visit");
    }
    @Test void briefLineSettlingPausesBlockDwellAndNeverCreditsUncertainTime() {
        var dwell = new GazeRegionDwell(); var block = region("caller", 1, "WEBCAM");
        dwell.observe(block, 1000); dwell.pause(2000);
        assertEquals(1000, dwell.elapsed(6000)); assertFalse(dwell.claimReady(6000, 3000));
        dwell.observe(block, 6200);
        assertEquals(1000, dwell.elapsed(6200)); assertFalse(dwell.claimReady(8100, 3000));
        assertTrue(dwell.claimReady(8200, 3000));
        dwell.pause(8300); dwell.observe(region("other", 1, "WEBCAM"), 8500);
        assertEquals(0, dwell.elapsed(8500));
    }
    @Test void leavingOrChangingRegionInputOrVersionRestartsDwell() {
        var dwell = new GazeRegionDwell();
        dwell.observe(region("caller", 1, "WEBCAM"), 1000);
        dwell.observe(region("validator", 1, "WEBCAM"), 3900);
        assertFalse(dwell.claimReady(4000, 3000));
        dwell.observe(region("validator", 2, "WEBCAM"), 6500);
        assertFalse(dwell.claimReady(7000, 3000));
        dwell.observe(region("validator", 2, "MOUSE_PROXY"), 9000);
        assertFalse(dwell.claimReady(10000, 3000));
        dwell.reset();
        assertFalse(dwell.claimReady(15000, 3000));
    }
    @Test void originalPromptRemainsInspectableAfterStopButNeverLeaksIntoAnotherSession() {
        var context = new GazeContextService();
        var block = region("caller", 1, "WEBCAM");
        context.capturePrompt("original prepared prompt", block, 1000);
        context.clear(); // Stop/pause clears sendable context.
        assertNull(context.preparedPrompt());
        assertEquals("original prepared prompt", context.inspect(session(1000, List.of(event(block, 4000)))).prompt());
        assertNull(context.inspect(session(2000, List.of())).prompt());
        context.beginSession();
        assertNull(context.inspect(session(1000, List.of())).prompt());
    }
    @Test void savedRecordingCanReconstructContextAfterRestartWithoutADwellTrigger() throws Exception {
        var caller = region("caller", 1, "WEBCAM"); var validator = region("validator", 1, "WEBCAM");
        var recording = session(1000, List.of(event(caller, 2000), event(validator, 2500), event(caller, 3000)));
        new GazeSessionStore(directory).save(recording);
        var reopened = new GazeSessionStore(directory).load().get(0);
        var inspection = new GazeContextService().inspect(reopened);
        assertTrue(inspection.description().contains("Reconstructed"));
        assertTrue(inspection.description().contains("does not prove"));
        assertTrue(inspection.prompt().contains("validator source"));
        assertTrue(inspection.prompt().contains("caller.java:1–5 [WEBCAM]\n/project/validator"));
        assertFalse(reopened.events.stream().anyMatch(e -> e.explained));
    }
    @Test void reconstructionExcludesOtherInputsOldVersionsAndExpiredHistory() {
        var events = List.of(event(region("expired", 1, "WEBCAM"), 2000),
                event(region("oldInput", 1, "WEBCAM"), 48000),
                event(region("proxy", 1, "MOUSE_PROXY"), 48500),
                event(region("oldVersion", 1, "WEBCAM"), 49000),
                event(region("oldVersion", 2, "WEBCAM"), 49500),
                event(region("target", 1, "WEBCAM"), 50000));
        String prompt = GazeSessionContext.reconstruct(session(1000, events)).prompt();
        assertFalse(prompt.contains("expired")); assertFalse(prompt.contains("oldInput"));
        assertFalse(prompt.contains("proxy")); assertFalse(prompt.contains("document version 1]\noldVersion source"));
        assertTrue(prompt.contains("document version 2"));
    }
    @Test void reconstructionHonorsDisabledHistoryAndProvidesAnActionableEmptyState() {
        var config = new GazeSessionMetadata.InitialConfiguration("WEBCAM", "OBSERVE", GazeScenario.OBSERVE.profile(), false, "", "");
        var metadata = new GazeSessionMetadata(new GazeSessionMetadata.Labels("", "", ""), config);
        var recording = new GazeSession("demo", 1000, 5000,
                List.of(event(region("a", 1, "WEBCAM"), 2000), event(region("b", 1, "WEBCAM"), 3000)), metadata, List.of());
        assertFalse(GazeSessionContext.reconstruct(recording).prompt().contains("Recent viewed-region sequence"));
        var empty = GazeSessionContext.reconstruct(session(1000, List.of()));
        assertNull(empty.prompt()); assertTrue(empty.description().contains("code editor"));
    }
}
