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

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "The opaque '_resultId' value from a "
                                    + "truncated tool-result stub. Copy it "
                                    + "verbatim — it's a bare UUID, no path "
                                    + "prefix or suffix.")),
            "required", List.of("id"));

    private final ToolResultStorage toolResultStorage;

    @Override
    public String name() {
        return "tool_result_read";
    }

    @Override
    public String description() {
        return "Fetch the full content of a previously-truncated tool "
                + "result. When a tool returns more bytes than fit "
                + "inline, the engine persists the full payload and "
                + "hands you a stub with a '_resultId'. Pass that id "
                + "here to get the original content back verbatim "
                + "(the exact JSON-serialised string the inline form "
                + "would have shown). The result is session-scoped "
                + "and only valid for the current session.";
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

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("id");
        if (!(raw instanceof String id) || id.isBlank()) {
            throw new ToolException("'id' is required");
        }
        try {
            String content = toolResultStorage.read(id.trim(), ctx);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id.trim());
            out.put("content", content);
            out.put("length", content.length());
            return out;
        } catch (IOException e) {
            throw new ToolException("tool_result_read failed: " + e.getMessage());
        }
    }
}
