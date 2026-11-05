package io.github.gazehighlighter;

import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;
import java.awt.Component;
import java.nio.file.Path;

/** Uses the IDE/platform save UI, including HTML extension and overwrite handling. */
final class GazeHtmlSaveDialog {
    private GazeHtmlSaveDialog() {}
    static @Nullable Path choose(Project project, Component parent, String title, String filename) {
        var descriptor = new FileSaverDescriptor(title,
                "Save a standalone HTML copy with captured source, notes and review information.", "html");
        var dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, parent);
        Path initialDirectory = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        var selected = dialog.save(initialDirectory, filename);
        // The platform chooser handles cancel and confirms replacements itself.
        return selected == null ? null : selected.getFile().toPath();
    }
}
