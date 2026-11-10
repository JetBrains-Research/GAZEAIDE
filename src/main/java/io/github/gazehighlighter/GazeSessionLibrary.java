package io.github.gazehighlighter;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import java.util.*;

/** Saves recordings outside the repository, in the IDE system directory. Disk work runs off the EDT. */
@Service(Service.Level.PROJECT)
public final class GazeSessionLibrary implements com.intellij.openapi.Disposable {
    private final Project project;
    private final GazeSessionStore store;
    private final Path directory;
    private final GazeSessionJournal journal;
    private final java.util.concurrent.ExecutorService writer=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"GAZEAIDE recording writer");t.setDaemon(true);return t;});
    private volatile List<GazeSession> sessions = List.of();
    private volatile boolean loaded;
    public boolean loaded() { return loaded; }
    private volatile String status = "Loading saved sessions…";
    public GazeSessionLibrary(Project project) {
        this.project = project;
        String identity = project.getBasePath() == null ? project.getLocationHash() : project.getBasePath();
        directory = Path.of(PathManager.getSystemPath(), "implicit-gaze", "sessions", GazeSessionStore.projectKey(identity));
        store = new GazeSessionStore(directory);
        journal=new GazeSessionJournal(directory);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<GazeSession> stored = new ArrayList<>(store.load());
                // A crash between saving the final snapshot and removing its journal must
                // not expose a second, apparently unfinished copy of the same recording.
                Set<Long> savedStarts = new HashSet<>();
                stored.forEach(s -> savedStarts.add(s.startMs));
                journal.recover().stream().filter(s -> !savedStarts.contains(s.startMs)).forEach(stored::add);
                synchronized (this) {
                    Map<String, GazeSession> combined = new LinkedHashMap<>();
                    sessions.forEach(s -> combined.put(GazeSessionStore.id(s), s));
                    stored.forEach(s -> combined.putIfAbsent(GazeSessionStore.id(s), s));
                    sessions = combined.values().stream().sorted(Comparator.comparingLong((GazeSession s) -> s.startMs).reversed()).toList();
                    status = "Recordings are saved locally in the IDE system folder.";
                }
            }
            catch (Exception e) { status = "Saved sessions could not be loaded. Restart the IDE before building blink references; other recordings can still be exported."; }
            finally { loaded = !status.startsWith("Saved sessions could not"); }
        });
    }
    public static GazeSessionLibrary getInstance(Project p) { return p.getService(GazeSessionLibrary.class); }
    public List<GazeSession> sessions() { return sessions; }
    public String status() { return status; }
    Path agentDirectory() { return directory.resolve("agent-examples"); }
    Path walkthroughDirectory() { return directory.resolve("walkthroughs"); }
    @FunctionalInterface private interface Write {void run() throws Exception;}
    private void journal(Write write) {writer.execute(()->{try{write.run();}catch(Exception e){status="Recording journal failed. Stop and export this session before closing the IDE.";if(!project.isDisposed())GazeNotify.warn(project,status);}});}
    void begin(GazeSession s){journal(()->journal.begin(s));}
    void append(long start,String kind,Object value){journal(()->journal.append(start,kind,value));}
    @Override public void dispose(){writer.shutdown();try{writer.awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    public void save(GazeSession session) {
        synchronized (this) {
            List<GazeSession> updated = new ArrayList<>(sessions);
            updated.removeIf(s -> GazeSessionStore.id(s).equals(GazeSessionStore.id(session)));
            updated.add(0, session); sessions = List.copyOf(updated); status = "Saving recording locally…";
        }
        writer.execute(() -> {
            try { store.save(session); journal.complete(session.startMs); status = "Recording saved locally. Export a copy for your study files."; }
            catch (Exception e) {
                status = "Local save failed. Export this recording before closing the IDE.";
                if (!project.isDisposed()) GazeNotify.warn(project, status);
            }
        });
    }
}
