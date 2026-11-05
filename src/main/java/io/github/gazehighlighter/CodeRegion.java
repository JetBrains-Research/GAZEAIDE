package io.github.gazehighlighter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded lexical blocks, not a compiler's definition/use or data-flow analysis. */
public record CodeRegion(int firstLine, int lastLine, String label, String text) {
    private static final Pattern PYTHON = Pattern.compile(
            "(?:async\\s+)?(?:def|class|if|elif|else|for|while|with|try|except|finally|match|case)\\b.*:");

    public static CodeRegion find(String text, int targetLine) {
        String[] lines = text.split("\\n", -1);
        int target = Math.max(0, Math.min(targetLine, lines.length - 1));
        String masked = mask(text);
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        int line = 0, bestStart = -1, bestEnd = lines.length;
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '\n') line++;
            else if (c == '{') stack.push(line);
            else if (c == '}' && !stack.isEmpty()) {
                int start = stack.pop();
                if (start <= target && target <= line && line > start
                        && line - start < bestEnd - bestStart) {
                    bestStart = start; bestEnd = line;
                }
            }
        }
        if (bestStart >= 0) {
            if (lines[bestStart].strip().equals("{") && bestStart > 0) bestStart--;
            return of(lines, bestStart, bestEnd);
        }
        String[] clean = masked.split("\\n", -1);
        for (int i = target; i >= 0; i--) {
            if (!PYTHON.matcher(clean[i].strip()).matches()) continue;
            int indent = indent(lines[i]), end = i + 1;
            while (end < lines.length && (clean[end].isBlank() || indent(lines[end]) > indent)) end++;
            if (target < end) return of(lines, i, end - 1);
        }
        int start = target / 8 * 8;
        return of(lines, start, Math.min(lines.length - 1, start + 7));
    }

    private static CodeRegion of(String[] lines, int start, int end) {
        String label = lines[start].strip();
        if (label.length() > 90) label = label.substring(0, 87) + "...";
        if (label.isBlank()) label = "Lines " + (start + 1) + "–" + (end + 1);
        StringBuilder code = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) code.append('\n');
            code.append(lines[i]);
            if (code.length() > 6000) { code.setLength(6000); code.append("\n[region truncated]"); break; }
        }
        return new CodeRegion(start, end, label, code.toString());
    }

    private static int indent(String s) {
        int n = 0;
        while (n < s.length() && Character.isWhitespace(s.charAt(n))) n++;
        return n;
    }

    /** Removes strings and comments while preserving offsets and line breaks. */
    static String mask(String text) {
        StringBuilder out = new StringBuilder(text);
        boolean block = false, comment = false, escaped = false, triple = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (c == '\n') { comment = false; continue; }
            if (comment) { out.setCharAt(i, ' '); continue; }
            if (block) {
                out.setCharAt(i, ' ');
                if (c == '*' && next == '/') { out.setCharAt(++i, ' '); block = false; }
                continue;
            }
            if (quote != 0) {
                out.setCharAt(i, ' ');
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) {
                    if (!triple) quote = 0;
                    else if (i + 2 < text.length() && next == c && text.charAt(i + 2) == c) {
                        out.setCharAt(++i, ' '); out.setCharAt(++i, ' '); quote = 0; triple = false;
                    }
                }
                continue;
            }
            if (c == '/' && next == '*') { block = true; out.setCharAt(i, ' '); out.setCharAt(++i, ' '); }
            else if ((c == '/' && next == '/') || c == '#') { comment = true; out.setCharAt(i, ' '); }
            else if (c == '\'' || c == '"' || c == '`') {
                quote = c; out.setCharAt(i, ' ');
                if (i + 2 < text.length() && next == c && text.charAt(i + 2) == c) {
                    triple = true; out.setCharAt(++i, ' '); out.setCharAt(++i, ' ');
                }
            }
        }
        return out.toString();
    }

    public static String identifier(String line, int column) {
        String clean = mask(line);
        if (column < 0 || column >= clean.length() || !part(clean.charAt(column))) return null;
        int s = column, e = column + 1;
        while (s > 0 && part(clean.charAt(s - 1))) s--;
        while (e < clean.length() && part(clean.charAt(e))) e++;
        String token = clean.substring(s, e);
        return Character.isJavaIdentifierStart(token.charAt(0)) && !KEYWORDS.contains(token) ? token : null;
    }

    private static boolean part(char c) { return Character.isJavaIdentifierPart(c); }
    private static final List<String> KEYWORDS = List.of("if", "else", "for", "while", "return", "class",
            "def", "public", "private", "protected", "static", "void", "int", "long", "double", "float",
            "boolean", "bool", "new", "null", "true", "false", "try", "catch", "finally", "throw", "throws",
            "import", "package", "const", "let", "var", "function", "async", "await", "this", "super",
            "in", "is", "and", "or", "not", "None", "True", "False", "with", "as", "pass", "break");

    public static List<int[]> occurrences(String text, String token) {
        List<int[]> result = new ArrayList<>();
        if (token == null || token.isBlank()) return result;
        String clean = mask(text);
        for (int from = 0; from < clean.length() && result.size() < 100;) {
            int p = clean.indexOf(token, from);
            if (p < 0) break;
            int e = p + token.length();
            if ((p == 0 || !part(clean.charAt(p - 1))) && (e == clean.length() || !part(clean.charAt(e))))
                result.add(new int[]{p, e});
            from = e;
        }
        return result;
    }
}
