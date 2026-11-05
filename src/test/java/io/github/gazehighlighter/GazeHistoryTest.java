package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GazeHistoryTest {
    static ViewedRegion region(String name, long version, String source) {
        return new ViewedRegion("/project/" + name + ".java", 1, 5, name, name + " source", version, source);
    }
    @Test void retainsAlternationButCoalescesContinuousViewing() {
        GazeHistory h = new GazeHistory(); var a = region("caller", 1, "WEBCAM"); var b = region("validator", 1, "WEBCAM");
        h.add(a, 1000); h.add(a, 1200); h.add(b, 2000); h.add(a, 3000);
        assertEquals(List.of(a, b, a), h.recent(3100, "WEBCAM"));
    }
    @Test void expiresHistoryAndKeepsRealAndProxySourcesSeparate() {
        GazeHistory h = new GazeHistory();
        h.add(region("old", 1, "WEBCAM"), 100);
        h.add(region("mouse", 1, "MOUSE_PROXY"), 46000);
        h.add(region("simulated", 1, "MOUSE_SIMULATION"), 46100);
        assertTrue(h.recent(46200, "WEBCAM").isEmpty());
        assertEquals(1, h.recent(46200, "MOUSE_PROXY").size());
        assertEquals(1, h.recent(46200, "MOUSE_SIMULATION").size());
    }
    @Test void invalidatesAllVersionsOfAnEditedFile() {
        GazeHistory h = new GazeHistory(); var a = region("a", 1, "WEBCAM");
        h.add(a, 1000); h.add(region("b", 1, "WEBCAM"), 2000); h.add(region("a", 2, "WEBCAM"), 3000);
        h.invalidate(a.filePath()); assertEquals("b", h.recent(3100, "WEBCAM").get(0).label());
        assertEquals(1, h.recent(3100, "WEBCAM").size());
    }
    @Test void promptIncludesViewedPairOrderedRevisitsAndVersions() {
        var a = region("caller", 7, "WEBCAM"); var b = region("validator", 8, "WEBCAM");
        String p = GazePrompt.build("validate(x)", "current code", null, List.of(a, b, a));
        assertTrue(p.contains("caller.java:1–5 [WEBCAM]\n/project/validator.java:1–5 [WEBCAM]\n/project/caller"));
        assertTrue(p.contains("document version 7")); assertTrue(p.contains("validator source"));
    }
    @Test void promptBoundsSourceContextAndDoesNotInventASequenceWhenDisabled() {
        String p = GazePrompt.build("x", "body", null, List.of());
        assertFalse(p.contains("Recent viewed"));
        GazeHistory h = new GazeHistory();
        for (int i = 0; i < 100; i++) h.add(region("file" + i, 1, "WEBCAM"), 1000 + i);
        assertEquals(12, h.recent(2000, "WEBCAM").size());
    }
}
