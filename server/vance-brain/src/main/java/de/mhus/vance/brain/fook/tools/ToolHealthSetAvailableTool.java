package de.mhus.vance.brain.fook.tools;

import de.mhus.vance.api.tools.ToolSafety;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.shared.toolhealth.ToolHealthDocument;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Mark a tool as {@code OK} again, clearing the previous DOWN state. */
@Component
@RequiredArgsConstructor
public class ToolHealthSetAvailableTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "scope", Map.of(
                            "type", "string",
                            "enum", List.of("SESSION", "USER", "PROJECT", "TENANT", "GLOBAL")),
                    "scopeId", Map.of("type", "string"),
                    "toolName", Map.of("type", "string"),
                    "note", Map.of("type", "string")),
            "required", List.of("scope", "toolName"));

    private final ToolHealthService toolHealthService;

    @Override public String name() { return "tool_health_set_available"; }
    @Override public String description() {
        return "Mark a tool as OK in the tool-health document. Used after "
                + "a successful probe confirms the tool is back.";
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
        String note = ToolHealthReadTool.stringParamOrNull(params, "note");

        ToolHealthDocument doc = toolHealthService.markAvailable(
                ctx.tenantId(), scope,
                scopeId == null
                        ? ToolHealthSetUnavailableTool.defaultScopeId(scope, ctx)
                        : scopeId,
                toolName, note,
                ToolHealthSetUnavailableTool.callerLabel(ctx));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolName", toolName);
        out.put("scope", doc.getScope().name());
        out.put("status", doc.getStatus().name());
        return out;
    }
}
