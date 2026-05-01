package de.mhus.vance.brain.tools.process;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists every think-process in the current session — name, engine,
 * status. Primary so the LLM always knows who else is around without
 * an extra discovery step.
 */
@Component
@RequiredArgsConstructor
public class ProcessListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "includeTerminated", Map.of(
                            "type", "boolean",
                            "description", "Include processes in terminal states "
                                    + "(STOPPED, DONE, STALE). Default false — those are "
                                    + "audit-only and clutter the live view.")),
            "required", List.of());

    private final ThinkProcessService thinkProcessService;

    @Override
    public String name() {
        return "process_list";
    }

    @Override
    public String description() {
        return "List all think-processes in the current session — name, "
                + "engine, status, optional goal.";
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
        String sessionId = ctx.sessionId();
        if (sessionId == null) {
            throw new ToolException("process_list requires a session scope");
        }
        boolean includeTerminated = params != null
                && Boolean.TRUE.equals(params.get("includeTerminated"));

        List<ThinkProcessDocument> docs = thinkProcessService.findBySession(
                ctx.tenantId(), sessionId);
        List<Map<String, Object>> rows = new ArrayList<>(docs.size());
        int hidden = 0;
        for (ThinkProcessDocument doc : docs) {
            if (!includeTerminated && isTerminated(doc.getStatus())) {
                hidden++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", doc.getName());
            row.put("status", doc.getStatus() == null ? null : doc.getStatus().name());
            row.put("engine", doc.getThinkEngine());
            if (doc.getThinkEngineVersion() != null) {
                row.put("engineVersion", doc.getThinkEngineVersion());
            }
            if (doc.getTitle() != null) row.put("title", doc.getTitle());
            if (doc.getGoal() != null) row.put("goal", doc.getGoal());
            row.put("isCurrent", doc.getId() != null && doc.getId().equals(ctx.processId()));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processes", rows);
        out.put("count", rows.size());
        if (!includeTerminated && hidden > 0) {
            out.put("hiddenTerminated", hidden);
        }
        return out;
    }

    private static boolean isTerminated(ThinkProcessStatus s) {
        return s == ThinkProcessStatus.CLOSED;
    }
}
