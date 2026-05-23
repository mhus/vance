package de.mhus.vance.brain.agrajag.tools;

import de.mhus.vance.api.tools.ToolSafety;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.shared.toolhealth.ToolHealthDocument;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Read the narrowest tool-health document matching the caller's scope. */
@Component
@RequiredArgsConstructor
public class ToolHealthReadTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "toolName", Map.of(
                            "type", "string",
                            "description", "Tool name to look up."),
                    "scope", Map.of(
                            "type", "string",
                            "enum", List.of("SESSION", "USER", "PROJECT", "TENANT", "GLOBAL"),
                            "description",
                            "Optional explicit scope. Omit to use the cascade.")),
            "required", List.of("toolName"));

    private final ToolHealthService toolHealthService;

    @Override public String name() { return "tool_health_read"; }
    @Override public String description() {
        return "Read the current tool-health document and its history for "
                + "a tool. Uses the scope cascade by default; pass `scope` "
                + "to force a particular layer.";
    }
    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public ToolSafety safety() { return ToolSafety.SAFE_PROBE; }
    @Override public Set<String> requiresEngineRoles() {
        return Set.of("tool-health-reader");
    }
    @Override public Set<String> labels() { return Set.of("read-only"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String toolName = stringParam(params, "toolName");
        String scopeStr = stringParamOrNull(params, "scope");

        Optional<ToolHealthDocument> doc;
        if (scopeStr == null) {
            doc = toolHealthService.lookup(
                    ctx.tenantId(), ctx.sessionId(), ctx.userId(),
                    ctx.projectId(), toolName);
        } else {
            // Explicit-scope lookup is currently implemented as a
            // single-scope cascade input; passing only one matching id
            // makes the cascade return that one entry if present.
            ToolHealthScope scope = parseScope(scopeStr);
            doc = toolHealthService.lookup(
                    ctx.tenantId(),
                    scope == ToolHealthScope.SESSION ? ctx.sessionId() : null,
                    scope == ToolHealthScope.USER ? ctx.userId() : null,
                    scope == ToolHealthScope.PROJECT ? ctx.projectId() : null,
                    toolName);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolName", toolName);
        if (doc.isEmpty()) {
            out.put("status", "OK");
            out.put("present", false);
            return out;
        }
        ToolHealthDocument d = doc.get();
        out.put("present", true);
        out.put("status", d.getStatus().name());
        out.put("scope", d.getScope().name());
        out.put("scopeId", d.getScopeId());
        out.put("since", d.getSince());
        out.put("expectedRecoveryAt", d.getExpectedRecoveryAt());
        out.put("lastNote", d.getLastNote());
        out.put("lastClassification",
                d.getLastClassification() == null ? null : d.getLastClassification().name());
        out.put("cooldowns", d.getCooldowns());
        out.put("history", d.getHistory());
        return out;
    }

    static String stringParam(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) throw new ToolException("Missing required param: " + key);
        return v.toString();
    }

    static @org.jspecify.annotations.Nullable String stringParamOrNull(
            Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    static ToolHealthScope parseScope(String s) {
        try {
            return ToolHealthScope.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("Invalid scope: " + s);
        }
    }
}
