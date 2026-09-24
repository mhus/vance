package de.mhus.vance.brain.hactar;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shared base for the {@code hactar_*} self-steering tools: role-gated (only
 * the Hactar engine sees them — the headless phase machine makes no LLM
 * calls, so in practice only the session-mode identity ever calls them),
 * each operating on the calling process' run.
 *
 * <p>Mirrors {@code WowbaggerBaseTool}: the calling process is re-fetched
 * from the service (the tool runs inside an agent turn whose engine-param
 * snapshot may be stale against the run service's persists).
 */
abstract class HactarBaseTool implements de.mhus.vance.toolpack.Tool {

    @Override
    public java.util.Set<String> requiresEngineRoles() {
        return java.util.Set.of(HactarEngine.ROLE);
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public boolean contributesPrak() {
        return false;
    }

    static ThinkProcessDocument process(ThinkProcessService thinkProcessService, ToolInvocationContext ctx) {
        return thinkProcessService
                .findById(ctx.processId())
                .orElseThrow(() -> new ToolException("hactar: process '" + ctx.processId() + "' not found"));
    }

    static @Nullable String stringParam(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    static @Nullable Boolean boolParam(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return null;
    }

    static @Nullable Map<String, Object> mapParam(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof Map<?, ?> m
                ? m.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                e -> String.valueOf(e.getKey()),
                                Map.Entry::getValue,
                                (a, b) -> b,
                                java.util.LinkedHashMap::new))
                : null;
    }

    static @Nullable List<String> stringListParam(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof String s && !s.isBlank())
                    .map(item -> (String) item)
                    .toList();
        }
        return null;
    }
}
