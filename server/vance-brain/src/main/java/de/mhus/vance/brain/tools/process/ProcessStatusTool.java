package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Detailed view of one think-process — same fields as
 * {@code process_list} plus chat-history size. Secondary because the
 * LLM rarely needs more than what {@code process_list} returns.
 */
@Component
@RequiredArgsConstructor
public class ProcessStatusTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Process name within the current session.")),
            "required", List.of("name"));

    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;

    @Override
    public String name() {
        return "process_status";
    }

    @Override
    public String description() {
        return "Get detail for one think-process in the current session "
                + "— engine, status, goal, current chat-history length.";
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
        String sessionId = ctx.sessionId();
        if (sessionId == null) {
            throw new ToolException("process_status requires a session scope");
        }
        Object rawName = params == null ? null : params.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        ThinkProcessDocument doc = thinkProcessService
                .findByName(ctx.tenantId(), sessionId, name)
                .orElseThrow(() -> new ToolException(
                        "Process '" + name + "' not found in current session"));

        int totalMessages = chatMessageService.history(
                ctx.tenantId(), sessionId, doc.getId()).size();
        int activeMessages = chatMessageService.activeHistory(
                ctx.tenantId(), sessionId, doc.getId()).size();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", doc.getName());
        out.put("status", doc.getStatus() == null ? null : doc.getStatus().name());
        out.put("engine", doc.getThinkEngine());
        out.put("engineVersion", doc.getThinkEngineVersion());
        out.put("title", doc.getTitle());
        out.put("goal", doc.getGoal());
        out.put("totalMessages", totalMessages);
        out.put("activeMessages", activeMessages);
        out.put("isCurrent", doc.getId() != null && doc.getId().equals(ctx.processId()));
        return out;
    }
}
