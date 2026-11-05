package io.github.gazehighlighter;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CoverageTrackerTest {
    @Test void onlyTheNamedScenarioEnablesViewedLineTintIncludingWhenProfilesAreIdentical() {
        var settings = new GazeResearchSettings();
        for (var scenario : GazeScenario.values()) {
            settings.getState().scenario = scenario.name();
            assertEquals(scenario == GazeScenario.COVERAGE,
                    settings.activeScenario(scenario.profile()).highlightsViewedLines(), scenario.title);
        }
        settings.getState().scenario = GazeScenario.COVERAGE.name();
        assertFalse(settings.activeScenario(GazeScenario.RELATIONSHIPS.profile()).highlightsViewedLines());
    }
    @Test void measurementAndHotspotsWorkWithoutAnyEditorMarkup() {
        var fixture = new Fixture();
        var tracker = new CoverageTracker(fixture.editor, false);
        tracker.flush(0, 5200); tracker.markHeavy(0); tracker.onLeave(0);
        assertEquals(1, tracker.getReadCount()); assertEquals(1, tracker.getHeavyCount());
        assertEquals(1, tracker.getExplainedCount()); assertEquals(5200, tracker.getHotspots().get(0).totalMs);
        assertEquals(0, fixture.markupCalls.get());
        tracker.dispose(); assertEquals(0, fixture.markupCalls.get());
    }
    @Test void dedicatedScenarioPaintsOnlyAfterAccumulatedThresholdAndClearsOnDispose() {
        var fixture = new Fixture();
        var tracker = new CoverageTracker(fixture.editor, true);
        tracker.flush(0, 300); assertEquals(0, fixture.added.get());
        tracker.flush(0, 200); assertEquals(1, fixture.added.get());
        tracker.dispose(); assertEquals(1, fixture.removed.get());
    }

    private static final class Fixture {
        final AtomicInteger markupCalls = new AtomicInteger(), added = new AtomicInteger(), removed = new AtomicInteger();
        final Document document = proxy(Document.class, (name, args) -> switch (name) {
            case "getLineCount" -> 1;
            case "getLineStartOffset" -> 0;
            case "getLineEndOffset" -> 8;
            case "getText" -> "code();";
            default -> throw new AssertionError(name);
        });
        final RangeHighlighter highlighter = proxy(RangeHighlighter.class, (name, args) -> switch (name) {
            case "isValid" -> true;
            default -> throw new AssertionError(name);
        });
        final MarkupModel markup = proxy(MarkupModel.class, (name, args) -> switch (name) {
            case "addRangeHighlighter" -> { added.incrementAndGet(); yield highlighter; }
            case "removeHighlighter" -> { removed.incrementAndGet(); yield null; }
            default -> throw new AssertionError(name);
        });
        final Editor editor = proxy(Editor.class, (name, args) -> switch (name) {
            case "isDisposed" -> false;
            case "getDocument" -> document;
            case "getMarkupModel" -> { markupCalls.incrementAndGet(); yield markup; }
            default -> throw new AssertionError(name);
        });
    }
    private interface Call { Object invoke(String name, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.invoke(method.getName(), args)));
    }
}
