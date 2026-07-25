package de.mhus.vance.brain.agrajag.tools;

import de.mhus.vance.api.tools.ToolSafety;
import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Set or refresh a cooldown entry on a tool-health document. */
@Component
@RequiredArgsConstructor
public class ToolHealthSetCooldownTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "scope", Map.of(
                            "type", "string",
                            "enum", List.of("SESSION", "USER", "PROJECT", "TENANT", "GLOBAL"), "description", "Scope level the health record applies to (SESSION, USER, PROJECT, TENANT, or GLOBAL)."),
                    "scopeId", Map.of("type", "string", "description", "Id within `scope` (session/user/project/tenant id). Omit to use the caller's current scope id."),
                    "toolName", Map.of("type", "string", "description", "Name of the tool this record is about, e.g. `web_fetch`."),
                    "errorSignature", Map.of("type", "string", "description", "Signature string identifying the specific failure class this record targets."),
                    "userId", Map.of(
                            "type", "string",
                            "description",
                            "Restrict the cooldown to one user (typical for permission). Omit for scope-wide."),
                    "duration", Map.of(
                            "type", "string",
                            "description", "ISO-8601 Duration, e.g. PT30M, PT24H."),
                    "classification", Map.of(
                            "type", "string",
                            "enum", List.of(
                                    "TECHNICALLY_BROKEN",
                                    "USER_SPECIFIC_TECHNICAL",
                                    "USER_PERMISSION",
                                    "USER_INPUT",
                                    "INTERMITTENT"), "description", "Why the tool is failing/unavailable — its failure classification."),
                    "note", Map.of("type", "string", "description", "Optional free-text operator note stored with the record.")),
            "required", List.of("scope", "toolName", "errorSignature",
                    "duration", "classification"));

    private final ToolHealthService toolHealthService;

    @Override public String name() { return "tool_health_set_cooldown"; }
    @Override public String description() {
        return "Set or refresh a cooldown entry on a tool-health document. "
                + "Cooldowns suppress further Agrajag spawns for the same "
                + "(toolName, signature[, userId]) until the duration expires.";
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
        String note = ToolHealthReadTool.stringParamOrNull(params, "note");
        Duration duration;
        try {
            duration = Duration.parse(ToolHealthReadTool.stringParam(params, "duration"));
        } catch (RuntimeException e) {
            throw new ToolException("Invalid duration: " + e.getMessage());
        }
        ToolHealthClassification classification;
        try {
            classification = ToolHealthClassification.valueOf(
                    ToolHealthReadTool.stringParam(params, "classification")
                            .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("Invalid classification");
        }

        ToolHealthCooldown cd = toolHealthService.setCooldown(
                ctx.tenantId(), scope,
                scopeId == null
                        ? ToolHealthSetUnavailableTool.defaultScopeId(scope, ctx)
                        : scopeId,
                toolName, signature, userId, classification, duration, note);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolName", toolName);
        out.put("scope", scope.name());
        out.put("errorSignature", cd.getErrorSignature());
        out.put("userId", cd.getUserId());
        out.put("nextSpawnAllowedAt", cd.getNextSpawnAllowedAt());
        out.put("hits", cd.getHits());
        return out;
    }
}
