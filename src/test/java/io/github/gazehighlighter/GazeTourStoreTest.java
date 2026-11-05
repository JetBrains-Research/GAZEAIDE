package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GazeTourStoreTest {
    @TempDir Path directory;
    @Test void tourNotesReviewMarksAndExplanationsSurviveReloadAndLateWritesCannotReplaceNewerEdits() throws Exception {
        var store = new GazeTourStore(directory); var tour = GazeReviewTourTest.tour();
        var step = tour.steps.get(0); step.note = "test empty input"; step.reviewed = true; step.behavior = "returns value";
        store.save(10000, 2, GazeTourStore.snapshot(tour));
        step.note = "old"; store.save(10000, 1, GazeTourStore.snapshot(tour));
        var recovered = new GazeTourStore(directory).load(10000);
        assertEquals("test empty input", recovered.steps.get(0).note); assertTrue(recovered.steps.get(0).reviewed);
        assertEquals("returns value", recovered.steps.get(0).behavior); assertNull(store.load(20000));
    }
    @Test void persistedSnapshotsRequireMatchingTextAsWellAsFileAndVersionForGazeSelection() {
        var tour = GazeReviewTourTest.tour();
        var different = new ViewedRegion("/a.java", 1, 10, "changed", "throw error;", 7, "WEBCAM");
        assertNull(tour.match(different));
        var r = new ViewedRegion("/a", 2, 3, "code", "b\nc", 7, "WEBCAM");
        assertTrue(GazeReviewTour.matchesSource("a\nb\nc\nd", r));
        assertFalse(GazeReviewTour.matchesSource("a\nb\nchanged\nd", r));
    }
    @Test void reopeningBeforeBackgroundSaveFinishesRetainsLatestNotes() throws Exception {
        var store = new GazeTourStore(directory); var tour = GazeReviewTourTest.tour();
        String old = GazeTourStore.snapshot(tour); store.save(10000, 1, old);
        tour.steps.get(0).note = "latest note";
        String latest = GazeTourStore.snapshot(tour); store.remember(10000, 2, latest);
        store.save(10000, 1, old);
        assertEquals("latest note", store.load(10000).steps.get(0).note);
        store.save(10000, 2, latest);
        assertEquals("latest note", new GazeTourStore(directory).load(10000).steps.get(0).note);
    }
}
