package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a UTF-8 text file from the session workspace. Truncates at
 * {@link WorkspaceProperties#getDefaultReadCharCap()} by default — the
 * result carries a {@code truncated} flag so the LLM can decide whether
 * to raise the cap via {@code maxChars}.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceReadTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Relative path inside the workspace."),
                    "maxChars", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum characters to return. 0 or negative "
                                            + "means use the server default cap.")),
            "required", List.of("path"));

    private final WorkspaceService workspace;
    private final WorkspaceProperties properties;

    @Override
    public String name() {
        return "workspace_read";
    }

    @Override
    public String description() {
        return "Read a text file from the session workspace. Returns the "
                + "file content; if the file is longer than the cap, only "
                + "the prefix is returned and 'truncated' is true.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = stringOrThrow(params, "path");
        int cap = properties.getDefaultReadCharCap();
        Object rawMax = params == null ? null : params.get("maxChars");
        if (rawMax instanceof Number n && n.intValue() > 0) {
            cap = n.intValue();
        }
        try {
            WorkspaceService.ReadResult r = workspace.read(ctx.sessionId(), path, cap);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("content", r.text());
            out.put("truncated", r.truncated());
            out.put("totalChars", r.totalChars());
            return out;
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
