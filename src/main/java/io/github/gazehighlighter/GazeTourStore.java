package io.github.gazehighlighter;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Locally saved tour drafts, keyed by the associated recording's start time. */
final class GazeTourStore {
    private static final Gson JSON = new Gson();
    private final Path directory;
    private final Map<Long, Long> revisions = new HashMap<>();
    private final Map<Long, String> drafts = new HashMap<>();
    private final Object diskLock = new Object();
    GazeTourStore(Path directory) { this.directory = directory; }
    static String snapshot(GazeReviewTour tour) { return JSON.toJson(tour); }
    synchronized void remember(long start, long revision, String snapshot) {
        if (revision < revisions.getOrDefault(start, -1L)) return;
        revisions.put(start, revision); drafts.put(start, snapshot);
    }
    void save(long start, long revision, String snapshot) throws IOException {
        synchronized (diskLock) {
            synchronized (this) {
                if (revision < revisions.getOrDefault(start, -1L)) return;
                remember(start, revision, snapshot);
            }
            Files.createDirectories(directory);
            Path destination = directory.resolve(start + ".json"), temporary = Files.createTempFile(directory, "tour-", ".tmp");
            try {
                Files.writeString(temporary, snapshot);
                try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    GazeReviewTour load(long start) throws IOException {
        synchronized (this) { if (drafts.containsKey(start)) return restore(drafts.get(start)); }
        Path path = directory.resolve(start + ".json"); if (!Files.isRegularFile(path)) return null;
        return restore(Files.readString(path));
    }
    private static GazeReviewTour restore(String snapshot) {
        try {
            GazeReviewTour t = JSON.fromJson(snapshot, GazeReviewTour.class);
            if (t == null || t.steps == null || t.goal == null || t.scope == null || t.overview == null || t.provider == null) return null;
            for (var s : t.steps) if (s == null || s.region == null || s.id == null || s.behavior == null || s.rationale == null || s.check == null || s.uncertainty == null || s.note == null) return null;
            return t;
        } catch (RuntimeException e) { return null; }
    }
}
