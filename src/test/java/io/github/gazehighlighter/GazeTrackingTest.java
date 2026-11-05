package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GazeTrackingTest {
    @Test void warmedMedianRejectsAnIsolatedLargeSpike() {
        var filter = new GazePointFilter();
        filter.update(100, 200, 0, 130); filter.update(100, 200, 40, 130); filter.update(100, 200, 80, 130);
        assertEquals(new Point(100, 200), filter.update(1900, -900, 120, 130));
        assertEquals(new Point(100, 200), filter.update(100, 200, 160, 130));
    }
    @Test void sustainedMovesConvergeInsteadOfStickingToOldTarget() {
        var filter = new GazePointFilter(); filter.update(0, 0, 0, 130);
        Point point = null;
        for (long t = 40; t <= 1000; t += 40) point = filter.update(500, 300, t, 130);
        assertTrue(Math.abs(point.x - 500) <= 1); assertTrue(Math.abs(point.y - 300) <= 1);
    }
    @Test void filterResetsAfterMissingInvalidOrReversedSamples() {
        var filter = new GazePointFilter(); filter.update(0, 0, 0, 130);
        assertEquals(new Point(400, 300), filter.update(400, 300, 600, 130));
        assertEquals(new Point(400, 300), filter.update(-900, 0, 600, 130));
        assertNull(filter.update(Double.NaN, 0, 620, 130));
        assertEquals(new Point(80, 90), filter.update(80, 90, 640, 130));
        assertEquals(new Point(10, 20), filter.update(10, 20, 600, 130));
    }
    @Test void timeBasedFilterHasSimilarResponseAtDifferentSamplingRates() {
        var slow = new GazePointFilter(); var fast = new GazePointFilter();
        slow.update(0, 0, 0, 130); fast.update(0, 0, 0, 130);
        Point a = null, b = null;
        for (int t = 40; t <= 400; t += 40) a = slow.update(100, 100, t, 130);
        for (int t = 20; t <= 400; t += 20) b = fast.update(100, 100, t, 130);
        assertTrue(Math.abs(a.y - b.y) < 3);
    }
    @Test void aNewLineNeedsContinuousResidenceAndPendingSamplesHaveNoTarget() {
        var target = new GazeLineTarget();
        assertEquals(-1, target.update(4, 90, 90, 20, 0, 160));
        assertEquals(-1, target.update(4, 90, 90, 20, 120, 160));
        assertEquals(4, target.update(4, 90, 90, 20, 160, 160));
        assertEquals(-1, target.update(8, 170, 170, 20, 200, 160));
        assertEquals(-1, target.update(8, 170, 170, 20, 320, 160));
        assertEquals(8, target.update(8, 170, 170, 20, 360, 160));
    }
    @Test void tinyBoundaryCrossingsRetainLineButLargeMovesAndGapsDoNot() {
        var target = new GazeLineTarget(); target.update(4, 90, 90, 20, 0, 160);
        assertEquals(4, target.update(4, 90, 90, 20, 160, 160));
        assertEquals(4, target.update(5, 101, 110, 20, 200, 160));
        assertEquals(-1, target.update(5, 110, 110, 20, 240, 160));
        assertEquals(5, target.update(5, 110, 110, 20, 400, 160));
        assertEquals(-1, target.update(5, 110, 110, 20, 1000, 160));
        target.reset(); assertEquals(-1, target.update(5, 110, 110, 20, 1200, 160));
    }
    @Test void bouncingCandidatesCannotAccumulateTimeAcrossDifferentLines() {
        var target = new GazeLineTarget();
        for (int t = 0; t <= 2000; t += 40)
            assertEquals(-1, target.update(t % 80 == 0 ? 2 : 3, 0, 0, 20, t, 160));
    }
    @Test void defaultResponseIsSteadyAndAllPresetsHaveExplicitDelays() {
        assertEquals(GazeResponse.STEADY, GazeResponse.from("old"));
        assertEquals(GazeResponse.STEADY, GazeResponse.from(null));
        assertTrue(GazeResponse.STEADY.smoothingMs > GazeResponse.RESPONSIVE.smoothingMs);
        assertTrue(GazeResponse.STEADY.lineHoldMs > GazeResponse.RESPONSIVE.lineHoldMs);
        var state = new GazeInputSettings.State(); assertFalse(state.showCursor);
    }
    @Test void affineFitRecoversScaledOffsetCoordinatesIncludingNegativeScreenOrigins() {
        List<GazeCalibration.Sample> samples = new ArrayList<>();
        for (double x : new double[]{100, 500, 900}) for (double y : new double[]{100, 400, 700})
            samples.add(new GazeCalibration.Sample(x, y, 2*x + .2*y - 2000, -.1*x + 1.5*y + 50));
        var fit = GazeCalibration.fit(samples); assertNotNull(fit);
        assertEquals(-1720, fit.x(100, 400), 1e-6); assertEquals(640, fit.y(100, 400), 1e-6);
    }
    @Test void calibrationRejectsCollinearAndNonfiniteInputs() {
        assertNull(GazeCalibration.fit(List.of(new GazeCalibration.Sample(1,1,1,1),
                new GazeCalibration.Sample(2,2,2,2), new GazeCalibration.Sample(3,3,3,3))));
        assertNull(GazeCalibration.fit(List.of(new GazeCalibration.Sample(0,0,0,0),
                new GazeCalibration.Sample(1,0,1,0), new GazeCalibration.Sample(0,1,Double.NaN,1))));
    }
    @Test void robustTrainingSummaryRejectsOutliersButRequiresEnoughSamplesAndDuration() {
        var samples = new ArrayList<GazeCalibrationQuality.Sample>();
        for (int i = 0; i < 30; i++) samples.add(new GazeCalibrationQuality.Sample(100+i%3, 200+i%3, i*40));
        samples.add(new GazeCalibrationQuality.Sample(9000,-1000, 1200));
        var estimate = GazeCalibrationQuality.estimate(samples);
        assertEquals(30, estimate.retained()); assertTrue(Math.abs(estimate.y() - 201) < 1);
        assertTrue(GazeCalibrationQuality.usable(estimate, samples.size(), 1800));
        assertFalse(GazeCalibrationQuality.usable(GazeCalibrationQuality.estimate(samples.subList(0, 8)), 8, 1800));
    }
    @Test void oldBrowserFilteredCalibrationsAreExplicitlyInvalidated() {
        var settings = new GazeInputSettings(); var state = new GazeInputSettings.State(); state.calibrated = true;
        settings.loadState(state); assertFalse(settings.isCalibrated()); assertSame(GazeCalibration.IDENTITY, settings.getCalibration());
        assertTrue(settings.calibrationSummary().contains("Recalibrate"));
    }
}
