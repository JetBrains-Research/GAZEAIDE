package io.github.gazehighlighter;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.JBColor;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reversible markup only: no source edits, folding, or scrolling. Diagnostics retain their higher layer. */
final class GazePresentation {
    private final Editor editor;
    private final List<RangeHighlighter> shades = new ArrayList<>(), matches = new ArrayList<>();
    private final Map<Integer, CodeRegion> viewed = new LinkedHashMap<>();
    private final Map<Integer, Long> exposure = new LinkedHashMap<>();
    GazePresentation(Editor editor) { this.editor = editor; }

    void leave(CodeRegion region, long duration) {
        if (region == null) return;
        viewed.put(region.firstLine(), region);
        exposure.merge(region.firstLine(), Math.max(0, duration), Long::sum);
        // Bound markup/history for long sessions in very large files.
        if (viewed.size() > 300) {
            Integer first = viewed.keySet().iterator().next(); viewed.remove(first); exposure.remove(first);
        }
    }

    void show(CodeRegion active, String token) {
        remove(shades); remove(matches);
        if (editor.isDisposed()) return;
        var settings = GazeFeatureSettings.getInstance().getState();
        if (settings.paused) return;
        Document doc = editor.getDocument();
        int activeStart = active == null ? -1 : start(active), activeEnd = active == null ? -1 : end(active);
        if (settings.focus && active != null) {
            shade(0, activeStart, 0.22);
            shade(activeEnd, doc.getTextLength(), 0.22);
        }
        if (settings.fadeViewed) for (CodeRegion r : viewed.values()) {
            if (exposure.getOrDefault(r.firstLine(), 0L) < settings.fadeAfterMs) continue;
            int s = start(r), e = end(r);
            // Never obscure the current target, including overlapping/nested regions.
            if (active == null || e <= activeStart || s >= activeEnd) shade(s, e, 0.34);
            else { shade(s, Math.min(e, activeStart), 0.34); shade(Math.max(s, activeEnd), e, 0.34); }
        }
        if (settings.relationships && active != null && token != null) {
            String code = doc.getText(new TextRange(activeStart, Math.min(activeEnd, activeStart + 20000)));
            TextAttributes attrs = new TextAttributes(null,
                    new JBColor(new Color(255, 239, 179), new Color(82, 66, 24)),
                    new JBColor(new Color(140, 100, 0), new Color(225, 190, 90)), EffectType.BOXED, Font.PLAIN);
            for (int[] hit : CodeRegion.occurrences(code, token)) {
                RangeHighlighter h = editor.getMarkupModel().addRangeHighlighter(activeStart + hit[0],
                        activeStart + hit[1], HighlighterLayer.SELECTION - 20, attrs, HighlighterTargetArea.EXACT_RANGE);
                h.setErrorStripeTooltip("Matching name: " + token + " (lexical, not resolved references)");
                matches.add(h);
            }
        }
    }

    private int start(CodeRegion r) { return editor.getDocument().getLineStartOffset(Math.min(r.firstLine(), editor.getDocument().getLineCount() - 1)); }
    private int end(CodeRegion r) { return editor.getDocument().getLineEndOffset(Math.min(r.lastLine(), editor.getDocument().getLineCount() - 1)); }
    private void shade(int start, int end, double amount) {
        if (start >= end) return;
        Color fg = editor.getColorsScheme().getDefaultForeground(), bg = editor.getColorsScheme().getDefaultBackground();
        Color dim = new Color((int)(fg.getRed() * (1 - amount) + bg.getRed() * amount),
                (int)(fg.getGreen() * (1 - amount) + bg.getGreen() * amount),
                (int)(fg.getBlue() * (1 - amount) + bg.getBlue() * amount));
        TextAttributes attrs = new TextAttributes(); attrs.setForegroundColor(dim);
        shades.add(editor.getMarkupModel().addRangeHighlighter(start, end, HighlighterLayer.SYNTAX + 1,
                attrs, HighlighterTargetArea.EXACT_RANGE));
    }
    void clear() { remove(shades); remove(matches); }
    void reset() { clear(); viewed.clear(); exposure.clear(); }
    private static void remove(List<RangeHighlighter> list) {
        for (RangeHighlighter h : list) if (h.isValid()) h.dispose();
        list.clear();
    }
}
