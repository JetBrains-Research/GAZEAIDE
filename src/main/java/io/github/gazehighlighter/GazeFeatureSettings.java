package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

@State(name = "ImplicitGazeFeatures", storages = @Storage("implicit-gaze-features.xml"))
public final class GazeFeatureSettings implements PersistentStateComponent<GazeFeatureSettings.Options> {
    public static final class Options {
        public boolean focus = true;
        public boolean fadeViewed = true;
        public boolean relationships = true;
        public boolean historyContext = true;
        public boolean explanations = true;
        public boolean paused = false;
        public int stableMs = 500;
        public int fadeAfterMs = 1500;
        public int explainAfterMs = 3000;
        public int researchUiIntroVersion = 0;
    }
    private final Options options = new Options();
    public static GazeFeatureSettings getInstance() { return ApplicationManager.getApplication().getService(GazeFeatureSettings.class); }
    @Override public @NotNull Options getState() { return options; }
    @Override public void loadState(@NotNull Options state) {
        XmlSerializerUtil.copyBean(state, options);
        options.stableMs = Math.max(200, Math.min(2000, options.stableMs));
        options.fadeAfterMs = Math.max(500, Math.min(30000, options.fadeAfterMs));
        options.explainAfterMs = Math.max(1000, Math.min(15000, options.explainAfterMs));
    }
}
