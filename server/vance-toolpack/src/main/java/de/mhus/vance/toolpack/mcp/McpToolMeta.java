package de.mhus.vance.toolpack.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Tool metadata returned by an MCP server's {@code tools/list} call.
 * Pure Java view; the dispatcher-side {@code McpEndpointTool} wraps
 * this to satisfy {@link de.mhus.vance.toolpack.Tool}.
 *
 * <p>Schema per MCP spec:
 * <pre>
 *   {
 *     "name": "...",
 *     "description": "...",
 *     "inputSchema": { "type":"object", "properties":{...}, "required":[...] }
 *   }
 * </pre>
 */
public record McpToolMeta(
        String name,
        @Nullable String description,
        Map<String, Object> inputSchema) {

    public McpToolMeta {
        name = name == null ? "" : name;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }

    /**
     * Builds metadata from one entry of the {@code tools} array in the
     * {@code tools/list} response. Throws when {@code name} is missing.
     */
    @SuppressWarnings("unchecked")
    public static McpToolMeta fromMap(Map<String, Object> entry) {
        if (entry == null) throw new IllegalArgumentException("MCP tool entry is null");
        Object nameRaw = entry.get("name");
        if (!(nameRaw instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("MCP tool entry missing 'name'");
        }
        String description = entry.get("description") instanceof String d ? d : null;
        Map<String, Object> schema = entry.get("inputSchema") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>(Map.of("type", "object"));
        return new McpToolMeta(name.trim(), description, schema);
    }

    /**
     * Parses a {@code tools/list}-result map into a {@link McpToolMeta}
     * list. Tolerates missing or non-list {@code tools} field
     * (returns empty).
     */
    @SuppressWarnings("unchecked")
    public static List<McpToolMeta> fromListResult(@Nullable Object result) {
        if (!(result instanceof Map<?, ?> m)) return List.of();
        Object tools = ((Map<String, Object>) m).get("tools");
        if (!(tools instanceof List<?> list)) return List.of();
        List<McpToolMeta> out = new java.util.ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> me) {
                out.add(fromMap((Map<String, Object>) me));
            }
        }
        return List.copyOf(out);
    }
}
