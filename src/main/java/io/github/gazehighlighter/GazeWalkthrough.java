package io.github.gazehighlighter;

import java.util.ArrayList;
import java.util.List;

/** Chronological route derived from recorded block exposure. Revisits remain separate stops. */
public final class GazeWalkthrough {
    public static final class Stop {
        public final ViewedRegion region;
        public final long elapsedMs;
        public long dwellMs;
        public String note = "";
        private long lastEndMs;
        Stop(ViewedRegion region, long elapsedMs, long dwellMs) {
            this.region = region; this.elapsedMs = elapsedMs; this.dwellMs = dwellMs;
            this.lastEndMs = elapsedMs + dwellMs;
        }
        @Override public String toString() { return region.label() + "  [" + region.source() + "]"; }
    }
    private GazeWalkthrough() {}

    public static List<Stop> build(GazeSession session, long minimumExposureMs) {
        List<Stop> contiguous = new ArrayList<>();
        // Accumulate adjacent line visits first: reading across a block should not be discarded
        // merely because each individual line was viewed for less than the stability threshold.
        for (GazeEvent e : session.events) {
            if (e.region == null || e.dwellMs <= 0 || e.lineText.isBlank()) continue;
            long start = Math.max(0, e.elapsedMs - e.dwellMs);
            Stop previous = contiguous.isEmpty() ? null : contiguous.get(contiguous.size() - 1);
            if (previous != null && previous.region.key().equals(e.region.key())
                    && start <= previous.lastEndMs + 1000) {
                previous.dwellMs += e.dwellMs;
                previous.lastEndMs = e.elapsedMs;
            } else contiguous.add(new Stop(e.region, start, e.dwellMs));
        }
        return contiguous.stream().filter(s -> s.dwellMs >= minimumExposureMs).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public static String html(String project, List<Stop> stops) {
        return html(project, stops, null);
    }
    public static String html(String project, List<Stop> stops, GazeSessionMetadata metadata) {
        StringBuilder out = new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Recorded viewing trace</title><style>"
                + "body{font:16px system-ui;max-width:1000px;margin:32px auto;padding:0 20px;color:#24272b;background:#fff}"
                + "pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f1f3f5;padding:16px}"
                + "section{margin:36px 0}p{white-space:pre-wrap}a{color:#175db0}</style><body><h1>")
                .append(escape(project)).append(" — viewing trace</h1>");
        if (metadata != null) out.append("<p>").append(escape(metadata.summary())).append("</p>");
        out.append("<p>Recorded source snapshots. Viewing does not establish understanding or correctness. "
                        + "Input source and version are shown for every stop.</p><nav><ol>");
        for (int i = 0; i < stops.size(); i++) out.append("<li><a href=\"#stop-").append(i).append("\">")
                .append(escape(stops.get(i).region.label())).append("</a></li>");
        out.append("</ol></nav>");
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i); ViewedRegion r = stop.region;
            out.append("<section id=\"stop-").append(i).append("\"><h2>").append(i + 1).append(". ")
                    .append(escape(r.label())).append("</h2><p>").append(escape(r.location()))
                    .append("\nInput: ").append(escape(r.source())).append(" · Version: ").append(r.documentStamp())
                    .append(" · Recorded exposure: ").append(stop.dwellMs).append(" ms</p><p>")
                    .append(escape(stop.note)).append("</p><pre><code>").append(escape(r.text()))
                    .append("</code></pre></section>");
        }
        return out.append("</body></html>").toString();
    }
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
