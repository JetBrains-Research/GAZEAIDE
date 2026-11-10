package io.github.gazehighlighter;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Durable reduced payloads and receipts. Contains neither credentials nor full local sessions. */
final class GazeRemoteOutbox {
    private static final com.google.gson.Gson STORAGE_JSON = new com.google.gson.Gson();
    record Entry(String localKey, String binding, JsonObject payload, boolean sent) {}
    private final Path directory;
    GazeRemoteOutbox(Path directory) { this.directory = directory; }
    synchronized Entry find(GazeSession session, GazeRemoteConnection connection) throws IOException {
        String localKey = localKey(session, connection);
        return entries().stream().filter(e -> e.localKey.equals(localKey)).findFirst().orElse(null);
    }
    private static String localKey(GazeSession session, GazeRemoteConnection connection) {
        return GazeSessionStore.projectKey(GazeSessionStore.id(session) + "\n" + connection.binding());
    }
    synchronized Entry enqueue(GazeSession session, GazeRemoteConnection connection) throws IOException {
        String localKey = localKey(session, connection);
        Entry previous = find(session, connection); if (previous != null) return previous;
        Entry entry = new Entry(localKey, connection.binding(), GazeRemotePayload.session(session, connection, UUID.randomUUID().toString()), false);
        save(entry); return entry;
    }
    synchronized void markSent(Entry entry) throws IOException { save(new Entry(entry.localKey, entry.binding, entry.payload, true)); }
    synchronized List<Entry> entries() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        List<Entry> entries = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{24}\\.json")).sorted().toList()) {
                try {
                    if (Files.size(file) > GazeRemotePayload.MAX_BYTES + 4096) throw new IOException();
                    Entry entry = GazeRemotePayload.JSON.fromJson(Files.readString(file), Entry.class);
                    if (entry == null || !entry.localKey.matches("[a-f0-9]{24}") || !file.getFileName().toString().equals(entry.localKey + ".json") || !entry.binding.matches("[a-f0-9]{24}")
                            || entry.payload == null || !GazeRemotePayload.PROTOCOL.equals(entry.payload.get("protocol").getAsString())) throw new IOException();
                    entries.add(entry);
                } catch (RuntimeException | IOException ex) {
                    Path quarantine=directory.resolve("unreadable");Files.createDirectories(quarantine);
                    try{Files.move(file,quarantine.resolve(file.getFileName()+"-"+UUID.randomUUID()),StandardCopyOption.REPLACE_EXISTING);}catch(IOException ignored){/* Continue processing independent entries. */}
                }
            }
        }
        return List.copyOf(entries);
    }
    synchronized void clearPending() throws IOException {
        for (Entry entry : entries()) if (!entry.sent) Files.deleteIfExists(directory.resolve(entry.localKey + ".json"));
    }
    private void save(Entry entry) throws IOException {
        Files.createDirectories(directory); Path temporary = Files.createTempFile(directory, "upload-", ".tmp");
        try {
            Files.writeString(temporary, STORAGE_JSON.toJson(entry));
            Path destination = directory.resolve(entry.localKey + ".json");
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
