package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import java.util.*;

/** Saves recordings outside the repository, in the IDE system directory. Disk work runs off the EDT. */
@Service(Service.Level.PROJECT)
public final class GazeSessionLibrary {
    private final Project project;
    private final GazeSessionStore store;
    private final Path directory;
    private volatile List<GazeSession> sessions = List.of();
    private volatile String status = "Loading saved sessions…";
    public GazeSessionLibrary(Project project) {
        this.project = project;
        String identity = project.getBasePath() == null ? project.getLocationHash() : project.getBasePath();
        directory = Path.of(PathManager.getSystemPath(), "implicit-gaze", "sessions", GazeSessionStore.projectKey(identity));
        store = new GazeSessionStore(directory);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<GazeSession> stored = store.load();
                synchronized (this) {
                    Map<String, GazeSession> combined = new LinkedHashMap<>();
                    sessions.forEach(s -> combined.put(GazeSessionStore.id(s), s));
                    stored.forEach(s -> combined.putIfAbsent(GazeSessionStore.id(s), s));
                    sessions = combined.values().stream().sorted(Comparator.comparingLong((GazeSession s) -> s.startMs).reversed()).toList();
                    status = "Recordings are saved locally in the IDE system folder.";
                }
            }
            catch (Exception e) { status = "Saved sessions could not be loaded. New recordings can still be exported."; }
        });
    }
    public static GazeSessionLibrary getInstance(Project p) { return p.getService(GazeSessionLibrary.class); }
    public List<GazeSession> sessions() { return sessions; }
    public String status() { return status; }
    Path agentDirectory() { return directory.resolve("agent-examples"); }
    Path walkthroughDirectory() { return directory.resolve("walkthroughs"); }
    public void save(GazeSession session) {
        synchronized (this) {
            List<GazeSession> updated = new ArrayList<>(sessions);
            updated.removeIf(s -> GazeSessionStore.id(s).equals(GazeSessionStore.id(session)));
            updated.add(0, session); sessions = List.copyOf(updated); status = "Saving recording locally…";
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { store.save(session); status = "Recording saved locally. Export a copy for your study files."; }
            catch (Exception e) {
                status = "Local save failed. Export this recording before closing the IDE.";
                if (!project.isDisposed()) GazeNotify.warn(project, status);
            }
        });
    }
}
