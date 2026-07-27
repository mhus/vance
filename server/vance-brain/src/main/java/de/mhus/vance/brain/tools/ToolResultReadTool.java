package de.mhus.vance.brain.tools;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a previously-persisted tool result by its
 * {@link ToolResultStorage#STUB_RESULT_ID_KEY} handle. The companion
 * to {@link ToolResultStorage}'s truncation path: when a tool result
 * exceeds the inline threshold (default 32 KB) the engine writes
 * the full content to disk and returns the LLM a stub map with a
 * {@code _resultId}; this tool turns that handle back into the full
 * content.
 *
 * <p>Without this tool the LLM has no clean way to recover the full
 * result — the previous design exposed the absolute disk path under
 * {@code _storagePath}, which led Ford workers to try
 * {@code work_file_read} on it. Scratch has its own RootDir, so the
 * read always failed with "Path escapes RootDir"; the worker burned
 * its per-turn tool-iteration budget on retries and the parent Vogon
 * phase ended STALE (observed live on 2026-05-17 in the
 * school-essay E2E run).
 *
 * <p>Session-scoped — the resolved file path must stay under the
 * caller's session's {@code tool-results/} directory. Cross-session
 * peeks are rejected via {@link ToolResultStorage#read} which
 * normalises and asserts the prefix.
 */
@Component
@RequiredArgsConstructor
public class ToolResultReadTool implements Tool {

    /**
     * Default number of characters returned per read. Kept comfortably
     * above the {@link ToolResultStorage#DEFAULT_THRESHOLD_BYTES} inline
     * ceiling so a typical oversized result (32–64 KB article) comes
     * back in one or two reads, yet bounded so a single read can never
     * dump a multi-megabyte blob into the prompt.
     */
    static final int DEFAULT_WINDOW_CHARS = 48 * 1024;

    /** Hard ceiling on {@code maxChars}, regardless of what the LLM asks. */
    static final int MAX_WINDOW_CHARS = 128 * 1024;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "The opaque '_resultId' value from a "
                                    + "truncated tool-result stub. Copy it "
                                    + "verbatim — it's a bare UUID, no path "
                                    + "prefix or suffix."),
                    "offset", Map.of(
                            "type", "integer",
                            "description", "Character offset to start reading "
                                    + "from. Default 0. For content larger "
                                    + "than one window, pass the 'nextOffset' "
                                    + "from the previous read to page forward — "
                                    + "do NOT re-read from 0."),
                    "maxChars", Map.of(
                            "type", "integer",
                            "description", "Max characters to return in this "
                                    + "read. Default " + DEFAULT_WINDOW_CHARS
                                    + ", capped at " + MAX_WINDOW_CHARS + ".")),
            "required", List.of("id"));

    private final ToolResultStorage toolResultStorage;

    @Override
    public String name() {
        return "tool_result_read";
    }

    @Override
    public String description() {
        return "Fetch the content of a previously-truncated tool "
                + "result. When a tool returns more bytes than fit "
                + "inline, the engine persists the full payload and "
                + "hands you a stub with a '_resultId'. Pass that id "
                + "here to get the original content back verbatim "
                + "(the exact JSON-serialised string the inline form "
                + "would have shown). Content is returned in windows: "
                + "when 'hasMore' is true, call again with "
                + "offset='nextOffset' to page forward instead of "
                + "re-reading from the start. The result is "
                + "session-scoped and only valid for the current session.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean contributesPrak() {
        // Re-reads a prior tool result — adds nothing new.
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    /**
     * Opt out of the oversized-output truncation path. This tool's whole
     * purpose is to surface a stored oversized result; re-truncating its
     * output would persist a fresh stub under a new {@code _resultId} and
     * hand back another preview — an infinite regress. The tool bounds
     * its own output via the {@code maxChars} window instead.
     */
    @Override
    public boolean bypassOutputTruncation() {
        return true;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("id");
        if (!(raw instanceof String id) || id.isBlank()) {
            throw new ToolException("'id' is required");
        }
        int offset = intParam(params, "offset", 0);
        if (offset < 0) {
            throw new ToolException("'offset' must be >= 0");
        }
        int maxChars = intParam(params, "maxChars", DEFAULT_WINDOW_CHARS);
        if (maxChars <= 0) {
            maxChars = DEFAULT_WINDOW_CHARS;
        }
        maxChars = Math.min(maxChars, MAX_WINDOW_CHARS);

        try {
            String content = toolResultStorage.read(id.trim(), ctx);
            int total = content.length();
            int start = Math.min(offset, total);
            int end = Math.min(start + maxChars, total);
            String window = content.substring(start, end);
            boolean hasMore = end < total;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id.trim());
            out.put("offset", start);
            out.put("content", window);
            out.put("returnedChars", window.length());
            out.put("totalChars", total);
            out.put("hasMore", hasMore);
            if (hasMore) {
                out.put("nextOffset", end);
            }
            return out;
        } catch (IOException e) {
            throw new ToolException("tool_result_read failed: " + e.getMessage());
        }
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object v = params == null ? null : params.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new ToolException("'" + key + "' must be an integer, got: '" + s + "'");
            }
        }
        throw new ToolException("'" + key + "' must be an integer");
    }
}
