package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import java.awt.Rectangle;
import static org.junit.jupiter.api.Assertions.*;

class GazeCalibrationRunTest {
    static final Rectangle SCREEN = new Rectangle(-1920, 0, 1920, 1080);
    private long clock;
    private void capture(GazeCalibrationRun run, double additionalValidationBias, boolean outlier) {
        double[] target = run.target();
        clock += GazeCalibrationRun.SETTLE_MS; run.tick(clock);
        for (int i = 0; i < 40; i++) {
            clock += 40;
            double x = SCREEN.x + target[0]*SCREEN.width + 80;
            double y = SCREEN.y + target[1]*SCREEN.height - 60 + (run.checking() ? additionalValidationBias : 0);
            run.sample(x + (outlier && i == 30 ? 2000 : 0), y, clock);
        }
        clock += 200; run.tick(clock);
    }
    @Test void requiresAllNineTrainingPointsAndFiveFreshValidationTargets() {
        var run = new GazeCalibrationRun(SCREEN, null, clock);
        for (int i = 0; i < 8; i++) capture(run, 0, true);
        assertFalse(run.checking()); assertNull(run.calibration()); assertNull(run.validation());
        capture(run, 0, true); assertTrue(run.checking()); assertNotNull(run.calibration());
        for (int i = 0; i < 4; i++) capture(run, 0, false);
        assertNull(run.validation()); capture(run, 0, false);
        assertEquals(GazeCalibrationRun.Phase.DONE, run.phase());
        assertEquals(5, run.validation().targets()); assertEquals(200, run.validation().samples());
        assertEquals(0, run.validation().p90ErrorPx(), 1e-5);
    }
    @Test void stableButBiasedHeldOutSamplesReportPoorAccuracyWithoutFilteringItAway() {
        var run = new GazeCalibrationRun(SCREEN, null, clock);
        for (int i = 0; i < 9; i++) capture(run, 0, false);
        for (int i = 0; i < 5; i++) capture(run, 80, false);
        var q = run.validation(); assertNotNull(q);
        assertEquals(80, q.medianVerticalPx(), 1e-5); assertEquals(80, q.p90VerticalPx(), 1e-5);
        assertEquals(0, q.jitter90Px(), 1e-5); assertTrue(q.summary(20).contains("4.0 code lines"));
    }
    @Test void validationOnlyUsesExistingCorrectionAndIncludesOutliersInErrorStatistics() {
        var existing = new GazeCalibration(1, 0, -80, 0, 1, 60);
        var run = new GazeCalibrationRun(SCREEN, existing, clock);
        for (int i = 0; i < 5; i++) capture(run, 0, true);
        assertSame(existing, run.calibration()); assertEquals(200, run.validation().samples());
        assertEquals(0, run.validation().medianErrorPx(), 1e-5);
        assertTrue(run.validation().summary(20).contains("200 samples"));
    }
    private void failTargetBatch(GazeCalibrationRun run) {
        for (int attempt = 0; attempt < 3; attempt++) {
            clock += GazeCalibrationRun.SETTLE_MS; run.tick(clock);
            run.sample(100, 100, clock + 1);
            clock += GazeCalibrationRun.COLLECT_MS; run.tick(clock);
            assertEquals(GazeCalibrationRun.Phase.COLLECT, run.phase());
            clock += GazeCalibrationRun.MAX_COLLECT_MS; run.tick(clock);
        }
        assertEquals(GazeCalibrationRun.Phase.PAUSED, run.phase());
    }
    @Test void tooFewSamplesExtendThenPauseUntilExplicitRetryWithoutProducingACorrection() {
        var run = new GazeCalibrationRun(SCREEN, null, clock);
        failTargetBatch(run);
        clock += 60000; run.tick(clock); run.sample(100,100,clock);
        assertEquals(GazeCalibrationRun.Phase.PAUSED, run.phase());
        assertEquals(0,run.index()); assertNull(run.calibration()); assertNull(run.validation());
        run.retryTarget(clock); assertEquals(GazeCalibrationRun.Phase.SETTLE,run.phase());
        capture(run,0,false); assertEquals(1,run.index());
    }
    @Test void repeatedRetriesKeepCompletedTrainingAndEveryIndependentCheck() {
        var run = new GazeCalibrationRun(SCREEN, null, clock);
        for(int i=0;i<8;i++)capture(run,0,false);
        for(int batch=0;batch<3;batch++) {
            failTargetBatch(run); assertEquals(8,run.index()); assertNull(run.calibration());
            run.retryTarget(clock);
        }
        capture(run,0,false); assertTrue(run.checking()); assertEquals(0,run.index());
        var fitted=run.calibration();
        for(int i=0;i<2;i++)capture(run,0,false);
        failTargetBatch(run); assertEquals(2,run.index()); assertSame(fitted,run.calibration());
        assertNull(run.validation()); run.retryTarget(clock);
        for(int i=2;i<5;i++)capture(run,0,false);
        assertEquals(GazeCalibrationRun.Phase.DONE,run.phase());
        assertEquals(5,run.validation().targets()); assertEquals(200,run.validation().samples());
        assertEquals(0,run.validation().p90ErrorPx(),1e-5);
    }
    @Test void slowSamplesCanPassDuringExtendedCollectionWithoutRelaxingQualityGates() {
        var run = new GazeCalibrationRun(SCREEN, null, clock);
        clock += GazeCalibrationRun.SETTLE_MS; run.tick(clock);
        clock += GazeCalibrationRun.COLLECT_MS; run.tick(clock);
        assertEquals(GazeCalibrationRun.Phase.COLLECT,run.phase());
        for(int i=0;i<12;i++){clock+=250;run.sample(100,100,clock);}
        run.tick(clock); assertEquals(1,run.index()); assertEquals(GazeCalibrationRun.Phase.SETTLE,run.phase());
    }
    @Test void samplesDuringSettlingDoNotCount() {
        var run = new GazeCalibrationRun(SCREEN, GazeCalibration.IDENTITY, clock);
        for (int i = 0; i < 100; i++) run.sample(1,1, i);
        clock = GazeCalibrationRun.SETTLE_MS; run.tick(clock);
        clock += GazeCalibrationRun.MAX_COLLECT_MS; run.tick(clock);
        assertEquals(0, run.index()); assertTrue(run.message().contains("Retry"));
    }
}
