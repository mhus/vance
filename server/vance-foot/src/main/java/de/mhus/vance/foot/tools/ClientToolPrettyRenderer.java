package de.mhus.vance.foot.tools;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.tools.file.ClientFilePaths;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StyleParser;
import de.mhus.vance.foot.ui.Verbosity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Claude-Code-style cosmetic display for local {@link ClientTool} invocations.
 *
 * <p>Two lines per call: a header that names the tool and summarises the
 * salient parameter ({@code ⏺ Read(/abs/path)}), followed by a faint
 * result tail ({@code   ⎿  Read 1 234 chars (45 lines)}). Renders only
 * for bean tools — pack tools and the special {@code client_*} jobs
 * defined here. Errors land on the result line with an exception suffix.
 *
 * <p>The header line is truncated to the terminal width minus the
 * {@code ⏺ Display(…)} chrome, so a long shell command or path keeps
 * as much as fits before the {@code …} ellipsis kicks in. On the
 * fallback no-TTY case the terminal reports width 80.
 *
 * <p>Toggle via {@link FootConfig.ToolOutput#isEnabled()} (CLI flag
 * {@code --no-tool-output}). When the flag is off the render methods
 * are silent no-ops so the dispatch path doesn't need to gate the call.
 */
@Component
public class ClientToolPrettyRenderer {

    /** Hard floor for the summary budget so an extremely narrow terminal
     *  still shows something useful. Anything below this gets clamped up. */
    private static final int MIN_SUMMARY_WIDTH = 20;

    private final ChatTerminal terminal;
    private final FootConfig config;
    private final @Nullable AttributedStyle headerStyle;
    private final @Nullable AttributedStyle resultStyle;
    private final FileDiffRenderer diffRenderer;

    public ClientToolPrettyRenderer(ChatTerminal terminal, FootConfig config) {
        this.terminal = terminal;
        this.config = config;
        FootConfig.ToolOutput cfg = config.getUi().getToolOutput();
        this.headerStyle = StyleParser.parse(cfg.getHeader());
        this.resultStyle = StyleParser.parse(cfg.getResult());
        this.diffRenderer = new FileDiffRenderer(terminal, cfg);
    }

    public boolean isEnabled() {
        return config.getUi().getToolOutput().isEnabled();
    }

    /**
     * Per-invocation render context produced by {@link #renderInvocation}
     * and consumed by {@link #renderResult} / {@link #renderError}.
     * Carries the file's pre-write content so the post-write diff can
     * be computed without forcing the tool to round-trip it back.
     */
    public static final class State {
        final String toolName;
        final Map<String, Object> params;
        final @Nullable String preContent;
        State(String toolName, Map<String, Object> params, @Nullable String preContent) {
            this.toolName = toolName;
            this.params = params;
            this.preContent = preContent;
        }
    }

    /** First line: {@code ⏺ Display(arg-summary)}. */
    public State renderInvocation(String toolName, Map<String, Object> params) {
        if (!isEnabled()) return new State(toolName, params, null);
        String display = displayName(toolName);
        // Chrome around the summary: "⏺ " + display + "(" … ")"
        int chromeLen = 2 + display.length() + 2;
        int maxSummary = Math.max(MIN_SUMMARY_WIDTH, terminal.width() - chromeLen);
        String summary = summariseParams(toolName, params, maxSummary);
        AttributedStringBuilder line = new AttributedStringBuilder();
        if (headerStyle != null) line.style(headerStyle);
        line.append("⏺ ").append(display);
        if (!summary.isEmpty()) {
            line.append("(").append(summary).append(")");
        }
        terminal.printlnStyled(Verbosity.INFO, line.toAttributedString());
        String pre = capturePreContent(toolName, params);
        return new State(toolName, params, pre);
    }

    /** Tail line: {@code   ⎿  <one-line outcome>}. */
    public void renderResult(State state, Map<String, Object> result) {
        if (!isEnabled()) return;
        String tail = summariseResult(state.toolName, result);
        AttributedStringBuilder line = new AttributedStringBuilder();
        if (resultStyle != null) line.style(resultStyle);
        line.append("  ⎿  ").append(tail);
        terminal.printlnStyled(Verbosity.INFO, line.toAttributedString());
        if (config.getUi().getToolOutput().isDiffEnabled()) {
            renderDiff(state, result);
        }
    }

    /** Tail line for failures: {@code   ⎿  Failed: <message>}. */
    public void renderError(State state, String message) {
        if (!isEnabled()) return;
        AttributedStringBuilder line = new AttributedStringBuilder();
        if (resultStyle != null) line.style(resultStyle);
        // Same chrome math as the header — the prefix is "  ⎿  Failed: ".
        int chromeLen = "  ⎿  Failed: ".length();
        int maxTail = Math.max(MIN_SUMMARY_WIDTH, terminal.width() - chromeLen);
        String text = message == null ? "(no detail)" : message;
        line.append("  ⎿  Failed: ").append(truncate(text, maxTail));
        terminal.printlnStyled(Verbosity.INFO, line.toAttributedString());
    }

    /**
     * Read the file's current content for write/edit tools so the diff
     * renderer has a "before" snapshot. Returns {@code null} for tools
     * we don't diff, for files that don't exist yet (new-file case),
     * or when the read fails — the diff renderer treats {@code null}
     * as the empty string, so the result still renders sanely.
     */
    private static @Nullable String capturePreContent(String toolName, Map<String, Object> params) {
        if (!"client_file_write".equals(toolName) && !"client_file_edit".equals(toolName)) {
            return null;
        }
        String rawPath = string(params, "path");
        if (rawPath.isEmpty()) return null;
        try {
            Path p = ClientFilePaths.resolve(rawPath);
            if (!Files.isRegularFile(p)) return "";
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Don't propagate — diff rendering is cosmetic. The actual
            // tool reads the file again and will report a real error
            // if anything is wrong with the path.
            return null;
        }
    }

    private void renderDiff(State state, Map<String, Object> result) {
        String postContent = computePostContent(state, result);
        if (postContent == null) return;
        String pre = state.preContent == null ? "" : state.preContent;
        diffRenderer.render(pre, postContent);
    }

    /**
     * Compute the post-write content for diffing without re-reading the
     * file (cheap, deterministic). For {@code client_file_write} the
     * full content is in the params; for {@code client_file_edit} we
     * reconstruct it by applying the unique replace to {@link State#preContent}.
     */
    private static @Nullable String computePostContent(State state, Map<String, Object> result) {
        return switch (state.toolName) {
            case "client_file_write" -> string(state.params, "content");
            case "client_file_edit" -> {
                if (state.preContent == null) yield null;
                String oldText = string(state.params, "oldText");
                String newText = string(state.params, "newText");
                if (oldText.isEmpty()) yield null;
                int at = state.preContent.indexOf(oldText);
                if (at < 0) yield null;
                yield state.preContent.substring(0, at)
                        + newText
                        + state.preContent.substring(at + oldText.length());
            }
            default -> null;
        };
    }

    // ---------------------------------------------------------------------
    // Display / param summary
    // ---------------------------------------------------------------------

    private static String displayName(String toolName) {
        return switch (toolName) {
            case "client_file_read" -> "Read";
            case "client_file_write" -> "Write";
            case "client_file_edit" -> "Edit";
            case "client_file_list" -> "List";
            case "client_file_grep" -> "Grep";
            case "client_file_find" -> "Find";
            case "client_file_count" -> "Count";
            case "client_file_head_tail" -> "HeadTail";
            case "client_exec_run" -> "Bash";
            case "client_exec_status" -> "ExecStatus";
            case "client_exec_stat" -> "ExecStat";
            case "client_exec_kill" -> "ExecKill";
            case "client_exec_tail" -> "ExecTail";
            case "client_javascript" -> "JavaScript";
            default -> toolName;
        };
    }

    /**
     * Builds the param-summary string for one tool call, capped at
     * {@code maxLen} visible characters. The cap is the *whole* summary's
     * budget — when a tool composes multiple fragments (path + pattern
     * etc.) the leading fragments stay readable and the long ones get
     * the ellipsis.
     */
    private static String summariseParams(String toolName,
                                          Map<String, Object> params,
                                          int maxLen) {
        if (params == null || params.isEmpty()) return "";
        String raw = switch (toolName) {
            case "client_file_read", "client_file_list", "client_file_count",
                    "client_file_head_tail" ->
                    pathOnly(params);
            case "client_file_write" -> {
                String path = string(params, "path");
                Object content = params.get("content");
                int chars = content instanceof String s ? s.length() : 0;
                yield path + (chars > 0 ? ", " + chars + " chars" : "");
            }
            case "client_file_edit" -> {
                String path = string(params, "path");
                Object oldS = params.get("oldText");
                int oldLen = oldS instanceof String s ? s.length() : 0;
                yield path + (oldLen > 0 ? ", replace " + oldLen + " chars" : "");
            }
            case "client_file_grep" -> {
                String pattern = string(params, "pattern");
                String path = string(params, "path");
                yield (pattern.isEmpty() ? "" : "/" + pattern + "/ ")
                        + (path.isEmpty() ? "." : path);
            }
            case "client_file_find" -> {
                String glob = string(params, "pathGlob");
                String path = string(params, "path");
                yield (glob.isEmpty() ? "*" : glob)
                        + " in " + (path.isEmpty() ? "." : path);
            }
            case "client_exec_run" -> oneLine(string(params, "command"));
            case "client_exec_status", "client_exec_stat", "client_exec_kill",
                    "client_exec_tail" ->
                    string(params, "id");
            case "client_javascript" -> oneLine(string(params, "code"));
            default -> shortDescribe(params, maxLen);
        };
        return truncate(raw, maxLen);
    }

    private static String summariseResult(String toolName, Map<String, Object> r) {
        if (r == null) return "ok";
        return switch (toolName) {
            case "client_file_read" -> {
                Object total = r.get("totalChars");
                Object trunc = r.get("truncated");
                yield "Read " + total + " chars"
                        + (Boolean.TRUE.equals(trunc) ? " (truncated)" : "");
            }
            case "client_file_write" -> {
                Object chars = r.get("chars");
                yield "Wrote " + chars + " chars";
            }
            case "client_file_edit" -> {
                Object replaced = r.get("replaced");
                Object total = r.get("totalChars");
                yield "Edited " + replaced + " occurrence(s), " + total + " chars total";
            }
            case "client_file_list" -> {
                Object count = r.get("count");
                yield "Listed " + count + " entries";
            }
            case "client_file_grep" -> {
                Object matches = r.get("matchCount");
                Object scanned = r.get("filesScanned");
                yield "Matched " + matches + " (scanned " + scanned + " files)";
            }
            case "client_file_find" -> {
                Object matches = r.get("matchCount");
                Object returned = r.get("returned");
                yield "Found " + matches + (matches != null && matches.equals(returned) ? "" : ", returned " + returned);
            }
            case "client_file_count" -> {
                Object lines = r.get("lines");
                Object chars = r.get("chars");
                yield lines + " lines, " + chars + " chars";
            }
            case "client_file_head_tail" -> {
                Object total = r.get("totalLines");
                List<?> rows = r.get("head") instanceof List<?> h ? h
                        : r.get("tail") instanceof List<?> t ? t : null;
                yield (rows == null ? 0 : rows.size()) + " rows of " + total + " total";
            }
            case "client_exec_run", "client_exec_status" -> {
                Object status = r.get("status");
                Object exit = r.get("exitCode");
                Object dur = r.get("durationMs");
                StringBuilder sb = new StringBuilder();
                sb.append(status);
                if (exit != null) sb.append(" (exit ").append(exit).append(")");
                if (dur != null) sb.append(", ").append(dur).append(" ms");
                yield sb.toString();
            }
            case "client_javascript" -> {
                if (r.containsKey("error")) {
                    yield "Error: " + r.get("error");
                }
                Object dur = r.get("durationMs");
                yield "ok" + (dur == null ? "" : " (" + dur + " ms)");
            }
            default -> "ok";
        };
    }

    private static String pathOnly(Map<String, Object> params) {
        return string(params, "path");
    }

    private static String string(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : "";
    }

    /** Flattens newlines to spaces so the header stays a single line. */
    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Trims {@code s} to {@code maxLen} visible characters; on overflow,
     * the last visible character is replaced with {@code …} so the cap
     * stays exact. Newlines are flattened first.
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        String oneLine = oneLine(s);
        if (maxLen <= 0 || oneLine.length() <= maxLen) return oneLine;
        if (maxLen == 1) return "…";
        return oneLine.substring(0, maxLen - 1) + "…";
    }

    private static String shortDescribe(Map<String, Object> params, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (i++ > 0) sb.append(", ");
            if (i > 3) { sb.append("…"); break; }
            sb.append(e.getKey()).append("=");
            Object v = e.getValue();
            // Per-value cap is a third of the overall budget — keeps a
            // 3-arg debug-describe roughly balanced.
            int perValue = Math.max(MIN_SUMMARY_WIDTH / 2, maxLen / 3);
            sb.append(v == null ? "null" : truncate(v.toString(), perValue));
        }
        return sb.toString();
    }
}
