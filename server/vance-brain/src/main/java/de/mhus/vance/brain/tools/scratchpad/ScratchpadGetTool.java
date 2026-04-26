package de.mhus.vance.brain.tools.scratchpad;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.ScratchpadService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a named scratchpad slot. Returns {@code found=false} if the
 * slot has never been written or has been deleted.
 */
@Component
@RequiredArgsConstructor
public class ScratchpadGetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "title", Map.of(
                            "type", "string",
                            "description", "Slot name to read.")),
            "required", List.of("title"));

    private final ScratchpadService scratchpad;

    @Override
    public String name() {
        return "scratchpad_get";
    }

    @Override
    public String description() {
        return "Read a named scratchpad slot. Returns the current content "
                + "or found=false if the slot doesn't exist.";
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
        if (!(rawTitle instanceof String title) || title.isBlank()) {
            throw new ToolException("'title' is required and must be a non-empty string");
        }
        Optional<MemoryDocument> hit = scratchpad.get(ctx.tenantId(), processId, title);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        if (hit.isPresent()) {
            MemoryDocument doc = hit.get();
            out.put("found", true);
            out.put("content", doc.getContent());
            out.put("memoryId", doc.getId());
            out.put("createdAt", doc.getCreatedAt() == null
                    ? null : doc.getCreatedAt().toString());
        } else {
            out.put("found", false);
            out.put("content", "");
        }
        return out;
    }
}
