package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.gazehighlighter.GazeTrackerStartup.Stage.*;

class GazeTrackerStartupTest {
    @Test void jcefCanRecoverAtItsSixtySecondStartupCheckButCannotHangForever() {
        var startup = new GazeTrackerStartup(0);
        startup.advance(BROWSER, 1_000);
        assertNull(startup.timeout(61_000));
        assertNull(startup.timeout(90_999));
        assertTrue(startup.timeout(91_000).contains("JCEF"));
    }

    @Test void replacingJcefAllowsExactlyOneFreshStartupWithIndependentDeadlines() {
        var startup = new GazeTrackerStartup(0);
        startup.advance(PAGE, 1_000);
        assertTrue(startup.recoverBrowser(65_000));
        assertEquals(ASSETS, startup.stage());
        assertNull(startup.timeout(65_000));
        startup.advance(BROWSER, 65_001);
        startup.advance(CAMERA, 70_000);
        startup.advance(READY, 71_000);
        assertFalse(startup.recoverBrowser(75_000), "Do not loop or restart IDE-wide JCEF");
    }

    @Test void repeatedOrLateCallbacksCannotExtendDeadlinesOrReplaceCameraProgress() {
        var startup = new GazeTrackerStartup(0);
        startup.advance(RUNTIME, 100);
        assertFalse(startup.advance(RUNTIME, 100_000));
        assertTrue(startup.timeout(120_100).contains("bundled runtime"));
        assertTrue(startup.advance(CAMERA, 120_101));
        assertFalse(startup.advance(PAGE, 120_102));
        assertEquals(CAMERA, startup.stage());
        assertTrue(startup.timeout(165_101).contains("Privacy & Security"));
    }

    @Test void everyUnfinishedStageHasATimeoutButAnAbsentFaceIsNotAStartupFailure() {
        for (var stage : GazeTrackerStartup.Stage.values()) {
            var startup = new GazeTrackerStartup(0);
            startup.advance(stage, 0);
            if (stage == READY) assertNull(startup.timeout(Long.MAX_VALUE));
            else {
                assertNull(startup.timeout(stage.timeoutMs - 1));
                assertNotNull(startup.timeout(stage.timeoutMs));
            }
        }
    }
}
