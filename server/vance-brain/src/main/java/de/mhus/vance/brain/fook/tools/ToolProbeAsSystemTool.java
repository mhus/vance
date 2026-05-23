package de.mhus.vance.brain.fook.tools;

import de.mhus.vance.api.tools.ToolSafety;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Re-invokes a target tool without any user identity — uses tenant
 * defaults / system credentials. Used by Fook to test "is this a
 * generic outage or specific to a user?".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolProbeAsSystemTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "toolName", Map.of("type", "string"),
                    "sampleInput", Map.of(
                            "type", "object",
                            "description",
                            "Minimal valid input for the target tool.")),
            "required", List.of("toolName", "sampleInput"));

    private final ToolDispatcher dispatcher;

    @Override public String name() { return "tool_probe_as_system"; }
    @Override public String description() {
        return "Re-invoke a SAFE_PROBE tool without user credentials. "
                + "Used to tell technical outages apart from user-specific "
                + "issues (token expired, permission denied).";
    }
    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public ToolSafety safety() { return ToolSafety.SAFE_PROBE; }
    @Override public Set<String> requiresEngineRoles() {
        return Set.of("tool-prober");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String toolName = ToolHealthReadTool.stringParam(params, "toolName");
        @SuppressWarnings("unchecked")
        Map<String, Object> sample = (Map<String, Object>)
                params.getOrDefault("sampleInput", Map.of());

        ToolDispatcher.Resolved resolved = dispatcher.resolve(toolName, ctx)
                .orElseThrow(() -> new ToolException(
                        "tool_probe_as_system: target tool not registered: " + toolName));
        if (resolved.tool().safety() != ToolSafety.SAFE_PROBE) {
            throw new ToolException(
                    "tool_probe_as_system: target tool '" + toolName
                            + "' is not SAFE_PROBE — refusing to probe");
        }

        ToolInvocationContext probeCtx = ToolProbeAsUserTool.withUser(ctx, null);
        // Clear userId for the system path — withUser keeps current when blank,
        // but we want explicit-null here.
        probeCtx = new ToolInvocationContext(
                probeCtx.tenantId(), probeCtx.projectId(),
                probeCtx.sessionId(), probeCtx.processId(),
                null, probeCtx.workingProjectId());

        long start = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolName", toolName);
        out.put("identity", "system");
        out.put("startedAt", Instant.now().toString());
        try {
            Map<String, Object> result = dispatcher.invoke(toolName, sample, probeCtx);
            out.put("success", true);
            out.put("durationMs", System.currentTimeMillis() - start);
            out.put("resultSummary", ToolProbeAsUserTool.summarise(result));
            return out;
        } catch (RuntimeException e) {
            out.put("success", false);
            out.put("durationMs", System.currentTimeMillis() - start);
            out.put("errorClass", e.getClass().getName());
            out.put("errorMessage", e.getMessage());
            log.debug("tool_probe_as_system failure tool='{}': {}",
                    toolName, e.toString());
            return out;
        }
    }
}
