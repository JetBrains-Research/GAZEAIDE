package io.github.gazehighlighter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Local session persistence; independent of IDE services so restart/round-trip behavior is testable. */
final class GazeSessionStore {
    private static final Gson JSON = new GsonBuilder().create();
    private final Path directory;
    GazeSessionStore(Path directory) { this.directory = directory; }
    static String projectKey(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String id(GazeSession session) { return session.startMs + "-" + session.endMs; }
    synchronized void save(GazeSession session) throws IOException {
        Files.createDirectories(directory);
        Path destination = directory.resolve(id(session) + ".json");
        Path temporary = Files.createTempFile(directory, "session-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(session), StandardCharsets.UTF_8);
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    synchronized List<GazeSession> load() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        List<GazeSession> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    GazeSession s = JSON.fromJson(Files.readString(file), GazeSession.class);
                    if (s == null || s.events == null || s.startMs <= 0 || s.endMs < s.startMs) continue;
                    result.add(new GazeSession(s.projectName, s.startMs, s.endMs, s.events, s.metadata,
                            s.configurationChanges == null ? List.of() : s.configurationChanges, s.blink));
                } catch (RuntimeException | IOException ignored) { /* One damaged recording must not hide the rest. */ }
            }
        }
        result.sort(Comparator.comparingLong((GazeSession s) -> s.startMs).reversed());
        return List.copyOf(result);
    }
}
