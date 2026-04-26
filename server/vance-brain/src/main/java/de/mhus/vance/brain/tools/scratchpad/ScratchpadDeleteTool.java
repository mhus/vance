package de.mhus.vance.brain.tools.scratchpad;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.memory.ScratchpadService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deletes a named scratchpad slot. The record stays in the database
 * (audit-readable, listable by the broader memory tools), but the
 * {@code scratchpad_*} tools stop returning it. Idempotent.
 */
@Component
@RequiredArgsConstructor
public class ScratchpadDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "title", Map.of(
                            "type", "string",
                            "description", "Slot name to delete.")),
            "required", List.of("title"));

    private final ScratchpadService scratchpad;

    @Override
    public String name() {
        return "scratchpad_delete";
    }

    @Override
    public String description() {
        return "Delete a named scratchpad slot. The slot disappears from "
                + "scratchpad_list and scratchpad_get returns found=false; "
                + "the prior content stays in the audit trail.";
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
        boolean deleted = scratchpad.delete(ctx.tenantId(), processId, title);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("deleted", deleted);
        return out;
    }
}
