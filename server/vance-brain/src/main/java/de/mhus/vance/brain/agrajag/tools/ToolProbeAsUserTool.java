package de.mhus.vance.brain.agrajag.tools;

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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Re-invokes a target tool with a substituted user identity so Agrajag can
 * tell user-specific failures (token expired, permissions wrong) apart
 * from generic technical issues.
 *
 * <p>The substituted identity is informational today — the target tool
 * receives a {@link ToolInvocationContext} with the desired {@code userId}
 * but otherwise the same scope. Tools that derive credentials from
 * settings keyed on {@code userId} see the substitution; tools that
 * carry their own context don't.
 *
 * <p>Only {@link ToolSafety#SAFE_PROBE} target tools are accepted —
 * Agrajag must never mutate state while diagnosing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolProbeAsUserTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "toolName", Map.of("type", "string"),
                    "userId", Map.of(
                            "type", "string",
                            "description",
                            "User identity to invoke the target tool as. Pass empty/omit "
                                    + "to use the diagnostic process's bound user."),
                    "sampleInput", Map.of(
                            "type", "object",
                            "description",
                            "Minimal valid input for the target tool. The tool must "
                                    + "tolerate it without side-effects.")),
            "required", List.of("toolName", "sampleInput"));

    private final ToolDispatcher dispatcher;

    @Override public String name() { return "tool_probe_as_user"; }
    @Override public String description() {
        return "Re-invoke a SAFE_PROBE tool with a substituted user "
                + "identity to distinguish user-specific failures from "
                + "generic technical issues.";
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
        String userId = ToolHealthReadTool.stringParamOrNull(params, "userId");
        @SuppressWarnings("unchecked")
        Map<String, Object> sample = (Map<String, Object>)
                params.getOrDefault("sampleInput", Map.of());

        // Resolve + verify safety pre-flight.
        ToolDispatcher.Resolved resolved = dispatcher.resolve(toolName, ctx)
                .orElseThrow(() -> new ToolException(
                        "tool_probe_as_user: target tool not registered: " + toolName));
        if (resolved.tool().safety() != ToolSafety.SAFE_PROBE) {
            throw new ToolException(
                    "tool_probe_as_user: target tool '" + toolName
                            + "' is not SAFE_PROBE — refusing to probe");
        }

        ToolInvocationContext probeCtx = withUser(ctx, userId);
        long start = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolName", toolName);
        out.put("userId", probeCtx.userId());
        out.put("startedAt", Instant.now().toString());
        try {
            Map<String, Object> result = dispatcher.invoke(toolName, sample, probeCtx);
            out.put("success", true);
            out.put("durationMs", System.currentTimeMillis() - start);
            out.put("resultSummary", summarise(result));
            return out;
        } catch (RuntimeException e) {
            out.put("success", false);
            out.put("durationMs", System.currentTimeMillis() - start);
            out.put("errorClass", e.getClass().getName());
            out.put("errorMessage", e.getMessage());
            log.debug("tool_probe_as_user failure tool='{}' user='{}': {}",
                    toolName, probeCtx.userId(), e.toString());
            return out;
        }
    }

    static ToolInvocationContext withUser(
            ToolInvocationContext ctx, @Nullable String userId) {
        return new ToolInvocationContext(
                ctx.tenantId(), ctx.projectId(), ctx.sessionId(),
                ctx.processId(),
                userId == null || userId.isBlank() ? ctx.userId() : userId,
                ctx.workingProjectId());
    }

    static String summarise(Map<String, Object> result) {
        if (result == null) return "null";
        String s = result.toString();
        return s.length() > 280 ? s.substring(0, 280) + "…" : s;
    }
}
