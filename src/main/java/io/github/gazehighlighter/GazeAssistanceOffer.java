package io.github.gazehighlighter;

import java.util.HashSet;
import java.util.Set;

/** Session-local offers: one per context key, at most once per 30 seconds; no popups or sending. */
final class GazeAssistanceOffer {
    static final int COOLDOWN_MS = 30_000;
    record Offer(String prompt, ViewedRegion region, String purpose) {}
    private final Set<String> offered = new HashSet<>();
    private long nextAt;
    private Offer current;
    boolean prepare(String key, String prompt, ViewedRegion region, String purpose, long now) {
        if (current != null || now < nextAt || !offered.add(key)) return false;
        current = new Offer(prompt, region, purpose); nextAt = now + COOLDOWN_MS; return true;
    }
    Offer current() { return current; }
    void dismiss(long now) { current = null; nextAt = Math.max(nextAt, now + COOLDOWN_MS); }
    void invalidate(String path, long now) { if (current != null && current.region.filePath().equals(path)) dismiss(now); }
    void reset() { current = null; offered.clear(); nextAt = 0; }
}
