package de.mhus.vance.brain.tools.scratchpad;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.ScratchpadService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists all active scratchpad slots for the current think-process,
 * with a small preview of each. Use {@code scratchpad_get} to read
 * the full content of a specific slot.
 */
@Component
@RequiredArgsConstructor
public class ScratchpadListTool implements Tool {

    private static final int PREVIEW_CHARS = 120;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final ScratchpadService scratchpad;

    @Override
    public String name() {
        return "scratchpad_list";
    }

    @Override
    public String description() {
        return "List active scratchpad slots for the current think-process "
                + "with content previews.";
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
        List<MemoryDocument> hits = scratchpad.list(ctx.tenantId(), processId);
        List<Map<String, Object>> rows = new ArrayList<>(hits.size());
        for (MemoryDocument doc : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", doc.getTitle() == null ? "" : doc.getTitle());
            row.put("memoryId", doc.getId());
            String content = doc.getContent() == null ? "" : doc.getContent();
            row.put("chars", content.length());
            row.put("preview", content.length() <= PREVIEW_CHARS
                    ? content
                    : content.substring(0, PREVIEW_CHARS) + "…");
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slots", rows);
        out.put("count", rows.size());
        return out;
    }
}
