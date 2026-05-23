package de.mhus.vance.brain.agrajag.tools;

import de.mhus.vance.api.tools.ToolSafety;
import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.shared.toolhealth.ToolHealthDocument;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Write a {@code DOWN} verdict into the tool-health document. */
@Component
@RequiredArgsConstructor
public class ToolHealthSetUnavailableTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "scope", Map.of(
                            "type", "string",
                            "enum", List.of("SESSION", "USER", "PROJECT", "TENANT", "GLOBAL")),
                    "scopeId", Map.of("type", "string"),
                    "toolName", Map.of("type", "string"),
                    "classification", Map.of(
                            "type", "string",
                            "enum", List.of(
                                    "TECHNICALLY_BROKEN",
                                    "USER_SPECIFIC_TECHNICAL",
                                    "INTERMITTENT")),
                    "expectedRecoveryAt", Map.of(
                            "type", "string",
                            "description",
                            "ISO-8601 instant, e.g. 2026-05-23T15:00:00Z. Optional."),
                    "note", Map.of("type", "string")),
            "required", List.of("scope", "toolName", "classification"));

    private final ToolHealthService toolHealthService;

    @Override public String name() { return "tool_health_set_unavailable"; }
    @Override public String description() {
        return "Mark a tool as DOWN in the tool-health document. Used by "
                + "Agrajag to record a technical-broken or "
                + "user-specific-technical diagnosis with an estimated "
                + "recovery window.";
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
        ToolHealthClassification classification = parseClassification(
                ToolHealthReadTool.stringParam(params, "classification"));
        String note = ToolHealthReadTool.stringParamOrNull(params, "note");
        String eta = ToolHealthReadTool.stringParamOrNull(params, "expectedRecoveryAt");
        Instant expectedRecoveryAt = eta == null ? null : Instant.parse(eta);

        ToolHealthDocument doc = toolHealthService.markUnavailable(
                ctx.tenantId(), scope,
                scopeId == null ? defaultScopeId(scope, ctx) : scopeId,
                toolName, classification, expectedRecoveryAt, note,
                callerLabel(ctx));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolName", toolName);
        out.put("scope", doc.getScope().name());
        out.put("status", doc.getStatus().name());
        out.put("since", doc.getSince());
        out.put("expectedRecoveryAt", doc.getExpectedRecoveryAt());
        return out;
    }

    static String defaultScopeId(ToolHealthScope scope, ToolInvocationContext ctx) {
        return switch (scope) {
            case SESSION -> nullToEmpty(ctx.sessionId());
            case USER -> nullToEmpty(ctx.userId());
            case PROJECT -> nullToEmpty(ctx.projectId());
            case TENANT -> nullToEmpty(ctx.tenantId());
            case GLOBAL -> "";
        };
    }

    static String callerLabel(ToolInvocationContext ctx) {
        return ctx.processId() == null ? "agrajag-tool" : "agrajag-engine/" + ctx.processId();
    }

    private static ToolHealthClassification parseClassification(String s) {
        try {
            return ToolHealthClassification.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("Invalid classification: " + s);
        }
    }

    private static String nullToEmpty(@org.jspecify.annotations.Nullable String s) {
        return s == null ? "" : s;
    }
}
