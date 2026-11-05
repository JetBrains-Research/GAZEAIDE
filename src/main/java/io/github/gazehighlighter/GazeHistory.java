package io.github.gazehighlighter;

import java.util.ArrayList;
import java.util.List;

/** Transient 45-second context history; separate from opt-in session recording. */
public final class GazeHistory {
    public record Entry(ViewedRegion region, long atMs) {}
    private final List<Entry> entries = new ArrayList<>();
    public void add(ViewedRegion region, long now) {
        entries.removeIf(e -> now - e.atMs() > 45_000);
        if (entries.isEmpty() || !entries.get(entries.size() - 1).region().key().equals(region.key()))
            entries.add(new Entry(region, now));
        else entries.set(entries.size() - 1, new Entry(region, now));
        while (entries.size() > 12) entries.remove(0);
    }
    public List<ViewedRegion> recent(long now, String source) {
        return entries.stream().filter(e -> now >= e.atMs() && now - e.atMs() <= 45_000)
                .map(Entry::region).filter(r -> r.source().equals(source)).toList();
    }
    public void invalidate(String path) { entries.removeIf(e -> e.region().filePath().equals(path)); }
    public void clear() { entries.clear(); }
}
