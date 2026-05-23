package de.mhus.vance.brain.fook.tools;

import de.mhus.vance.api.tools.ToolSafety;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Remove a cooldown entry matching {@code (errorSignature[, userId])}. */
@Component
@RequiredArgsConstructor
public class ToolHealthClearCooldownTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "scope", Map.of(
                            "type", "string",
                            "enum", List.of("SESSION", "USER", "PROJECT", "TENANT", "GLOBAL")),
                    "scopeId", Map.of("type", "string"),
                    "toolName", Map.of("type", "string"),
                    "errorSignature", Map.of("type", "string"),
                    "userId", Map.of("type", "string")),
            "required", List.of("scope", "toolName", "errorSignature"));

    private final ToolHealthService toolHealthService;

    @Override public String name() { return "tool_health_clear_cooldown"; }
    @Override public String description() {
        return "Clear a specific cooldown entry — used by Fook when the "
                + "original cause is resolved (token renewed, permissions "
                + "granted, transient issue gone).";
    }
    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public ToolSafety safety() { return ToolSafety.SAFE_PROBE; }
    @Override public Set<String> requiresEngineRoles() {
        return Set.of("tool-health-writer");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ToolHealthScope scope = ToolHealthReadTool.parseScope(
                ToolHealthReadTool.stringParam(params, "scope"));
        String scopeId = ToolHealthReadTool.stringParamOrNull(params, "scopeId");
        String toolName = ToolHealthReadTool.stringParam(params, "toolName");
        String signature = ToolHealthReadTool.stringParam(params, "errorSignature");
        String userId = ToolHealthReadTool.stringParamOrNull(params, "userId");

        toolHealthService.clearCooldown(
                ctx.tenantId(), scope,
                scopeId == null
                        ? ToolHealthSetUnavailableTool.defaultScopeId(scope, ctx)
                        : scopeId,
                toolName, signature, userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", true);
        out.put("toolName", toolName);
        out.put("errorSignature", signature);
        out.put("userId", userId);
        return out;
    }
}
