package io.github.gazehighlighter;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GazePersonalizationTest {
    private GazePersonalizationRun.Trial trial(int row, long start, double noise, double bias) {
        List<GazePersonalizationRun.Sample> samples = new ArrayList<>();
        double y = 100 + row*24;
        for (int i=0; i<48; i++) samples.add(new GazePersonalizationRun.Sample(200, y + bias + (i%2 == 0 ? noise : -noise), start + i*50));
        return new GazePersonalizationRun.Trial(row, y, start, samples);
    }
    private GazePersonalizationRun trained(double noise) {
        var run = new GazePersonalizationRun(24); long at = 10000;
        for (int row : GazePersonalizationRun.TRAIN_ROWS) { assertTrue(run.addTrial(trial(row, at, noise, 0))); at += 2400; }
        assertTrue(run.checking()); assertFalse(run.complete()); return run;
    }
    private GazePersonalizationRun.Result finished() {
        var run = trained(5); long at = 24400;
        for (int row : GazePersonalizationRun.CHECK_ROWS) { assertTrue(run.addTrial(trial(row, at, 5, 0))); at += 2400; }
        return run.result();
    }
    @Test void newCheckRowsNeverRetuneTheChosenPreset() {
        var run = trained(5); GazeResponse frozen = run.selected(); long at = 24400;
        for (int row : GazePersonalizationRun.CHECK_ROWS) {
            assertTrue(run.addTrial(trial(row, at, 90, 75))); at += 2400;
            assertEquals(frozen, run.selected());
        }
        assertTrue(run.complete()); assertEquals(frozen, run.result().response());
        assertTrue(run.result().validation().p90VerticalPx() > 24);
        assertFalse(run.addTrial(trial(0, at, 0, 0)));
    }
    @Test void missingNonfiniteAndOutOfOrderSamplesCannotCompleteATarget() {
        var run = new GazePersonalizationRun(24);
        assertFalse(run.addTrial(new GazePersonalizationRun.Trial(1, 124, 10000, List.of())));
        var invalid = new ArrayList<>(trial(1, 10000, 0, 0).samples());
        invalid.set(10, new GazePersonalizationRun.Sample(200, Double.NaN, 10500));
        assertFalse(run.addTrial(new GazePersonalizationRun.Trial(1, 124, 10000, invalid)));
        invalid = new ArrayList<>(trial(1, 10000, 0, 0).samples());
        invalid.set(10, invalid.get(9));
        assertFalse(run.addTrial(new GazePersonalizationRun.Trial(1, 124, 10000, invalid)));
        assertEquals(0, run.index()); assertThrows(IllegalStateException.class, run::result);
        assertFalse(run.addTrial(trial(4, 10000, 0, 0))); // check target cannot enter training
        assertTrue(run.addTrial(trial(1, 10000, 0, 0)));
    }
    @Test void strongerFilteringReducesJitterButCannotRepairPositionBias() {
        var trials = List.of(trial(3, 10000, 25, 0));
        var steady = GazePersonalizationRun.evaluate(trials, GazeResponse.STEADY, 24);
        var fast = GazePersonalizationRun.evaluate(trials, GazeResponse.RESPONSIVE, 24);
        assertTrue(steady.jitter90Px() < fast.jitter90Px());
        assertTrue(GazePersonalizationRun.evaluate(List.of(trial(3, 10000, 0, 60)), GazeResponse.STEADY, 24).p90VerticalPx() > 50);
    }
    @Test void oneProfileSurvivesPersistenceAndScenarioChangesAndCanBeDisabledOrReset() {
        var input = new GazeInputSettings(); input.getState().calibrated = true; input.getState().calibrationVersion = 2;
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            var display = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            input.getState().displayX = display.x; input.getState().displayY = display.y;
            input.getState().displayWidth = display.width; input.getState().displayHeight = display.height;
        }
        input.savePersonalResponse(GazePersonalResponseProfile.create(finished(), input.calibrationKey(), 24));
        var serialized = com.intellij.util.xmlb.XmlSerializer.serialize(input.getState());
        var recovered = new GazeInputSettings();
        recovered.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(serialized, GazeInputSettings.State.class));
        assertTrue(recovered.hasPersonalResponse()); assertTrue(recovered.isPersonalResponseActive(), new Gson().toJson(recovered.getState()));
        var response = recovered.getResponse();
        for (var scenario : GazeScenario.values()) {
            scenario.profile().applyTo(new GazeFeatureSettings.Options());
            assertTrue(recovered.isPersonalResponseActive()); assertEquals(response, recovered.getResponse());
        }
        recovered.setUsePersonalResponse(false); assertFalse(recovered.isPersonalResponseActive()); assertTrue(recovered.hasPersonalResponse());
        recovered.setUsePersonalResponse(true); assertTrue(recovered.isPersonalResponseActive());
        recovered.getState().calC += 10; assertFalse(recovered.isPersonalResponseActive());
        assertTrue(recovered.personalResponseSummary().contains("Calibration/display changed"));
        recovered.clearPersonalResponse(); assertFalse(recovered.hasPersonalResponse());
    }
    @Test void oldSettingsHaveNoInventedPersonalProfileAndJsonDoesNotContainRawSamples() {
        var input = new GazeInputSettings(); input.loadState(new GazeInputSettings.State());
        assertFalse(input.hasPersonalResponse()); assertEquals(GazeResponse.STEADY, input.getResponse());
        var profile = GazePersonalResponseProfile.create(finished(), "key", 24);
        String text = new Gson().toJson(profile); assertFalse(text.contains("rawX")); assertFalse(text.contains("trials"));
    }
}
