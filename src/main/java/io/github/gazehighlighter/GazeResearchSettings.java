package io.github.gazehighlighter;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Project-local research labels; no provider credentials or source code. */
@Service(Service.Level.PROJECT)
@State(name = "ImplicitGazeResearch", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class GazeResearchSettings implements PersistentStateComponent<GazeResearchSettings.Options> {
    public static final class Options {
        public String participant = "", task = "", condition = "", scenario = GazeScenario.CUSTOM.name();
        public String reviewPurpose = GazeReviewPurpose.COMPARE.name();
        public boolean quietOffers = true;
    }
    private Options options = new Options();
    public static GazeResearchSettings getInstance(Project project) { return project.getService(GazeResearchSettings.class); }
    @Override public @NotNull Options getState() { return options; }
    @Override public void loadState(@NotNull Options state) { options = state; }
    public GazeSessionMetadata.Labels labels() { return new GazeSessionMetadata.Labels(options.participant, options.task, options.condition); }
    public void setLabels(GazeSessionMetadata.Labels labels) {
        options.participant = labels.participant(); options.task = labels.task(); options.condition = labels.condition();
    }
    public GazeReviewPurpose reviewPurpose() { return GazeReviewPurpose.from(options.reviewPurpose); }
    public GazeScenario activeScenario(GazeFeatureProfile profile) {
        GazeScenario selected = GazeScenario.from(options.scenario);
        return selected != GazeScenario.CUSTOM && selected.profile().equals(profile) ? selected : GazeScenario.CUSTOM;
    }
}
