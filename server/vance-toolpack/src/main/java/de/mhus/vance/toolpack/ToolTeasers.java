package de.mhus.vance.toolpack;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One-line teasers for tool calls and their outcomes — the compact
 * "what is this call about" string shown on user-facing progress pings
 * ({@code StatusPayload.teaser}) and in the foot terminal's tool output.
 *
 * <p>This is the single authority for that line on both ends: the brain
 * ships the teaser over the progress side-channel, the foot client
 * renders the same string in its local tool header. One implementation,
 * so the terminal and the web UI cannot drift apart on what a call to
 * {@code file_write} looks like in one line.
 *
 * <p><b>Teasers are distilled, never raw.</b> Tool params and results may
 * carry large or sensitive payloads (file contents, commands); a teaser
 * names the <em>subject</em> of the call (path, command, query) and caps
 * everything it echoes — see {@link #MAX_LINES}, {@link #MAX_CHARS}. What
 * is never included: parameter values of settings/vault-style tools,
 * full file contents, and anything but the first few preview lines of a
 * written document.
 *
 * <p>Coverage is deliberately narrow — a {@code switch} over the tool
 * families where a subject line makes sense (file, exec, javascript,
 * web, doc), with {@code null} as the answer for everything else. A new
 * tool without a case simply shows no teaser; that is the intended
 * default, not a gap to fill in every tool class.
 *
 * <p>All output is sanitised: control and escape bytes (ESC/C0/C1/DEL)
 * are stripped from every echoed value before it enters a line, because
 * paths, commands and error text are attacker-influenceable and the
 * teaser is rendered in a terminal on the foot side.
 */
public final class ToolTeasers {

    /** Hard ceiling on teaser lines — the preview block stops here. */
    public static final int MAX_LINES = 8;

    /** Hard ceiling on teaser characters across all lines. */
    public static final int MAX_CHARS = 480;

    /** Per-line cap; longer echoes are cut with an ellipsis. */
    public static final int MAX_LINE_CHARS = 120;

    /** Preview lines shown for a whole-file write. */
    private static final int WRITE_PREVIEW_LINES = 5;

    /** Preview lines shown per side of an edit's mini diff. */
    private static final int EDIT_PREVIEW_LINES = 3;

    private ToolTeasers() {}

    /**
     * Subject line of the call, or {@code null} when the tool has no
     * meaningful subject. Single line, at most {@link #MAX_LINE_CHARS}
     * visible characters.
     */
    public static @Nullable String call(String toolName, @Nullable Map<String, Object> params) {
        if (params == null || params.isEmpty()) return null;
        String name = normalise(toolName);
        String raw =
                switch (name) {
                    case "file_read", "file_list", "file_count", "file_head_tail", "file_delete" -> pathOnly(params);
                    case "file_write" -> {
                        String path = string(params, "path");
                        Object content = params.get("content");
                        int chars = content instanceof String s ? s.length() : 0;
                        int lines = content instanceof String s ? countLines(s) : 0;
                        yield join(path, chars > 0 ? lines + " lines, " + chars + " chars" : null);
                    }
                    case "file_edit" -> {
                        String path = string(params, "path");
                        Object oldText = params.get("oldText");
                        int oldLen = oldText instanceof String s ? s.length() : 0;
                        yield join(path, oldLen > 0 ? "replace " + oldLen + " chars" : null);
                    }
                    case "file_grep" -> {
                        String pattern = string(params, "pattern");
                        String path = string(params, "path");
                        yield (pattern.isEmpty() ? "" : "/" + pattern + "/ ") + (path.isEmpty() ? "." : path);
                    }
                    case "file_find" -> {
                        String glob = string(params, "pathGlob");
                        String path = string(params, "path");
                        yield (glob.isEmpty() ? "*" : glob) + " in " + (path.isEmpty() ? "." : path);
                    }
                    case "exec_run" -> oneLine(string(params, "command"));
                    case "exec_status", "exec_stat", "exec_kill", "exec_tail" -> string(params, "id");
                    case "javascript" -> oneLine(string(params, "code"));
                    case "web_search" -> string(params, "query");
                    case "web_fetch" -> string(params, "url");
                    default -> name.startsWith("doc_") ? docSubject(params) : null;
                };
        return raw == null || raw.isBlank() ? null : capLine(oneLine(raw));
    }

    /**
     * Optional multi-line preview block: what a write/edit puts into the
     * document, in patch notation. {@code null} for everything else — the
     * block exists because "what was written" is the one question a
     * subject line cannot answer.
     *
     * <p>Preview lines are a teaser of the content, never the content:
     * at most {@value #WRITE_PREVIEW_LINES} lines of a write and
     * {@value #EDIT_PREVIEW_LINES} per side of an edit, each capped at
     * {@link #MAX_LINE_CHARS}.
     */
    public static @Nullable String preview(String toolName, @Nullable Map<String, Object> params) {
        if (params == null) return null;
        return switch (normalise(toolName)) {
            case "file_write" -> writePreview(params);
            case "file_edit" -> editPreview(params);
            default -> null;
        };
    }

    /**
     * Full call teaser for the wire: {@link #call} plus {@link #preview}
     * as one capped, possibly multi-line string. First line is always the
     * subject line, so a one-line renderer can just take the first line.
     */
    public static @Nullable String describe(String toolName, @Nullable Map<String, Object> params) {
        String call = call(toolName, params);
        if (call == null) return null;
        String preview = preview(toolName, params);
        if (preview == null) return call;
        return cap(call + "\n" + preview);
    }

    /**
     * One-line outcome of a completed call from its result map, or
     * {@code null} when the result carries nothing worth reporting.
     */
    public static @Nullable String outcome(String toolName, @Nullable Map<String, Object> result) {
        if (result == null || result.isEmpty()) return null;
        String raw =
                switch (normalise(toolName)) {
                    case "file_read" -> {
                        Object total = result.get("totalChars");
                        if (total == null) yield null;
                        yield "Read " + total + " chars"
                                + (Boolean.TRUE.equals(result.get("truncated")) ? " (truncated)" : "");
                    }
                    case "file_write" -> {
                        Object chars = result.get("chars");
                        yield chars == null ? null : "Wrote " + chars + " chars";
                    }
                    case "file_edit" -> {
                        Object replaced = result.get("replaced");
                        Object total = result.get("totalChars");
                        if (replaced == null && total == null) yield null;
                        yield "Edited " + replaced + " occurrence(s), " + total + " chars total";
                    }
                    case "file_list" -> {
                        Object count = result.get("count");
                        yield count == null ? null : "Listed " + count + " entries";
                    }
                    case "file_grep" -> {
                        Object matches = result.get("matchCount");
                        Object scanned = result.get("filesScanned");
                        if (matches == null) yield null;
                        yield "Matched " + matches + " (scanned " + scanned + " files)";
                    }
                    case "file_find" -> {
                        Object matches = result.get("matchCount");
                        if (matches == null) yield null;
                        Object returned = result.get("returned");
                        yield "Found " + matches + (matches.equals(returned) ? "" : ", returned " + returned);
                    }
                    case "file_count" -> {
                        Object lines = result.get("lines");
                        Object chars = result.get("chars");
                        if (lines == null && chars == null) yield null;
                        yield lines + " lines, " + chars + " chars";
                    }
                    case "file_head_tail" -> {
                        Object total = result.get("totalLines");
                        int rows = result.get("head") instanceof java.util.List<?> h
                                ? h.size()
                                : result.get("tail") instanceof java.util.List<?> t ? t.size() : 0;
                        if (total == null && rows == 0) yield null;
                        yield rows + " rows of " + total + " total";
                    }
                    case "exec_run", "exec_status" -> {
                        Object status = result.get("status");
                        Object exit = result.get("exitCode");
                        Object duration = result.get("durationMs");
                        if (status == null && exit == null && duration == null) yield null;
                        StringBuilder sb = new StringBuilder();
                        sb.append(status == null ? "done" : status);
                        if (exit != null) sb.append(" (exit ").append(exit).append(")");
                        if (duration != null) sb.append(", ").append(duration).append(" ms");
                        yield sb.toString();
                    }
                    case "javascript" -> {
                        if (result.containsKey("error")) {
                            yield "Error: " + result.get("error");
                        }
                        Object duration = result.get("durationMs");
                        yield "ok" + (duration == null ? "" : " (" + duration + " ms)");
                    }
                    case "web_search" -> {
                        Object count = result.get("count");
                        yield count == null ? null : count + " results";
                    }
                    default -> null;
                };
        return raw == null || raw.isBlank() ? null : capLine(oneLine(raw));
    }

    // ------------------------------------------------------------------
    // Preview blocks
    // ------------------------------------------------------------------

    private static @Nullable String writePreview(Map<String, Object> params) {
        if (!(params.get("content") instanceof String content) || content.isBlank()) return null;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : firstLines(content, WRITE_PREVIEW_LINES)) {
            out.add("+ " + line);
        }
        if (countLines(content) > WRITE_PREVIEW_LINES) {
            out.add("…");
        }
        return String.join("\n", out);
    }

    private static @Nullable String editPreview(Map<String, Object> params) {
        String oldText = string(params, "oldText");
        String newText = string(params, "newText");
        if (oldText.isEmpty() && newText.isEmpty()) return null;
        java.util.List<String> out = new java.util.ArrayList<>();
        appendDiffLines(out, "-", oldText, EDIT_PREVIEW_LINES);
        appendDiffLines(out, "+", newText, EDIT_PREVIEW_LINES);
        return String.join("\n", out);
    }

    private static void appendDiffLines(java.util.List<String> out, String marker, String text, int maxLines) {
        if (text.isEmpty()) {
            out.add(marker + " (empty)");
            return;
        }
        for (String line : firstLines(text, maxLines)) {
            out.add(marker + " " + line);
        }
        if (countLines(text) > maxLines) {
            out.add(marker + " …");
        }
    }

    /** First {@code max} lines of {@code text}, each capped and cleaned. */
    private static java.util.List<String> firstLines(String text, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (out.size() >= max) break;
            String cleaned = clean(line);
            out.add(capLine(cleaned.isEmpty() ? " " : cleaned));
        }
        return out;
    }

    private static int countLines(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /**
     * Tool names arrive as the LLM-facing name ({@code file_write}) or as
     * the foot backend name ({@code client_file_write}) — same params,
     * same subject. Folding the prefix keeps one case per family.
     */
    private static String normalise(String toolName) {
        String name = toolName == null ? "" : toolName;
        return name.startsWith("client_") ? name.substring("client_".length()) : name;
    }

    private static String pathOnly(Map<String, Object> params) {
        return string(params, "path");
    }

    /**
     * Document tools identify their subject by several keys depending on
     * the family (see {@code HistoryTagBuilder} for the same fallback
     * chain on results) — first one present wins.
     */
    private static String docSubject(Map<String, Object> params) {
        for (String key : new String[] {"path", "documentId", "docId", "name", "title"}) {
            String s = string(params, key);
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private static String string(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : "";
    }

    private static String join(String head, @Nullable String tail) {
        if (head.isEmpty()) return tail == null ? "" : tail;
        return tail == null ? head : head + ", " + tail;
    }

    /** Flattens to one line and strips control bytes. */
    private static String oneLine(String raw) {
        return clean(raw.replace('\n', ' ').replace('\r', ' '));
    }

    /**
     * Strips terminal control/escape bytes (ESC/C0/C1/DEL) — every echoed
     * value funnels through here before it can reach a terminal renderer.
     */
    private static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean control = c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F);
            sb.append(control ? ' ' : c);
        }
        return sb.toString().trim();
    }

    /** Caps one line to {@link #MAX_LINE_CHARS}, ellipsis on overflow. */
    private static String capLine(String s) {
        if (s.length() <= MAX_LINE_CHARS) return s;
        return s.substring(0, MAX_LINE_CHARS - 1) + "…";
    }

    /** Caps a multi-line teaser to {@link #MAX_LINES} / {@link #MAX_CHARS}. */
    private static String cap(String teaser) {
        String[] lines = teaser.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int needed = sb.length() == 0 ? line.length() : line.length() + 1;
            if (i >= MAX_LINES || sb.length() + needed > MAX_CHARS) {
                if (sb.length() < MAX_CHARS) {
                    sb.append('…');
                }
                break;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }
}
