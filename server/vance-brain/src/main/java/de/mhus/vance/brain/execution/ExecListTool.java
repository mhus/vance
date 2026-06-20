package de.mhus.vance.brain.execution;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * List every shell execution the brain knows about — its own jobs and
 * foot-side jobs reported via {@code exec-event} — within the caller's
 * tenant + project. The {@code owner} field tells callers which side
 * runs each entry; pair with {@code work_exec_stat} / {@code work_exec_tail} /
 * {@code work_exec_kill}, which now route via the registry.
 */
@Component
@RequiredArgsConstructor
public class ExecListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "onlyRunning", Map.of(
                            "type", "boolean",
                            "description", "If true, only RUNNING jobs are returned."),
                    "sessionId", Map.of(
                            "type", "string",
                            "description",
                                    "Restrict to one session in the current project. "
                                            + "Defaults to all sessions of this project."),
                    "ownerLabel", Map.of(
                            "type", "string",
                            "description",
                                    "Restrict to a single owner: 'brain', or "
                                            + "'foot:<editorId>' for a specific "
                                            + "foot client.")));

    private final ExecutionRegistryService registry;

    @Override
    public String name() {
        return "work_exec_list";
    }

    @Override
    public String description() {
        return "List shell executions across brain and connected foot "
                + "clients within the caller's tenant + project. Each "
                + "entry carries owner ('brain' or 'foot:<editorId>'), "
                + "status, command, lastOutputAt, exitCode. Use work_exec_stat "
                + "for details and work_exec_tail for output.";
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
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        boolean onlyRunning = false;
        Object rawRunning = params == null ? null : params.get("onlyRunning");
        if (rawRunning instanceof Boolean b) onlyRunning = b;
        String sessionId = stringOrNull(params, "sessionId");
        String ownerLabel = stringOrNull(params, "ownerLabel");

        ExecutionScopeFilter filter = new ExecutionScopeFilter(
                ctx.tenantId(),
                ctx.projectId(),
                sessionId,
                null,
                ownerLabel,
                onlyRunning);
        List<ExecutionRegistryEntry> entries = registry.list(filter);
        List<Map<String, Object>> rendered = new ArrayList<>(entries.size());
        for (ExecutionRegistryEntry e : entries) rendered.add(renderEntry(e));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rendered.size());
        out.put("executions", rendered);
        return out;
    }

    static Map<String, Object> renderEntry(ExecutionRegistryEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.executionId());
        m.put("owner", e.owner().label());
        m.put("status", e.status().name());
        m.put("command", e.command());
        if (e.sessionId() != null) m.put("sessionId", e.sessionId());
        if (e.projectId() != null) m.put("projectId", e.projectId());
        if (e.processId() != null) m.put("processId", e.processId());
        m.put("startedAt", e.startedAt().toString());
        m.put("lastOutputAt", e.lastOutputAt().toString());
        if (e.endedAt() != null) m.put("endedAt", e.endedAt().toString());
        if (e.exitCode() != null) m.put("exitCode", e.exitCode());
        return m;
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
