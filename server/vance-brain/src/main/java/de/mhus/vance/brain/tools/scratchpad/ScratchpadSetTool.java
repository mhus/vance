package de.mhus.vance.brain.tools.scratchpad;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.ScratchpadService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Writes a named scratchpad slot. Overwrites any existing entry with
 * the same title for the calling process — the previous value stays
 * persistent (superseded), so an engine can revisit prior states.
 */
@Component
@RequiredArgsConstructor
public class ScratchpadSetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "title", Map.of(
                            "type", "string",
                            "description", "Slot name. Re-using a title overwrites that slot."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Slot content. Plain text or markdown.")),
            "required", List.of("title", "content"));

    private final ScratchpadService scratchpad;

    @Override
    public String name() {
        return "scratchpad_set";
    }

    @Override
    public String description() {
        return "Write a named scratchpad slot for the current think-process. "
                + "Overwrites any existing entry with the same title; the prior "
                + "value is kept (superseded) for audit.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String processId = ctx.processId();
        if (processId == null) {
            throw new ToolException("Scratchpad tools require a think-process scope");
        }
        Object rawTitle = params == null ? null : params.get("title");
        Object rawContent = params == null ? null : params.get("content");
        if (!(rawTitle instanceof String title) || title.isBlank()) {
            throw new ToolException("'title' is required and must be a non-empty string");
        }
        if (!(rawContent instanceof String content)) {
            throw new ToolException("'content' is required and must be a string");
        }
        try {
            MemoryDocument saved = scratchpad.set(
                    ctx.tenantId(),
                    ctx.projectId() == null ? "" : ctx.projectId(),
                    ctx.sessionId(),
                    processId,
                    title,
                    content);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("title", title);
            out.put("memoryId", saved.getId());
            out.put("chars", content.length());
            return out;
        } catch (RuntimeException e) {
            throw new ToolException("Scratchpad set failed: " + e.getMessage(), e);
        }
    }
}
