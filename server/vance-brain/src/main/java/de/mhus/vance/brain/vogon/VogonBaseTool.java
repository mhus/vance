package de.mhus.vance.brain.vogon;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shared base for the {@code vogon_*} self-steering tools: role-gated (only
 * the Vogon engine sees them — the headless runner makes no LLM calls, so in
 * practice only the session-mode identity ever calls them), each operating
 * on the calling process' run.
 *
 * <p>Mirrors {@code HactarBaseTool}: the calling process is re-fetched from
 * the service (the tool runs inside an agent turn whose engine-param
 * snapshot may be stale against {@code rememberRunId}'s writes).
 */
abstract class VogonBaseTool implements de.mhus.vance.toolpack.Tool {

    @Override
    public java.util.Set<String> requiresEngineRoles() {
        return java.util.Set.of(VogonEngine.ROLE);
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
                .orElseThrow(() -> new ToolException("vogon: process '" + ctx.processId() + "' not found"));
    }

    /**
     * The run this process owns — the engine-param copy, no service
     * round-trip. The process document the caller passes is already
     * re-fetched ({@link #process}), so the id is fresh.
     */
    static @Nullable String runId(ThinkProcessDocument process) {
        Map<String, Object> params = process.getEngineParams();
        Object raw = params == null ? null : params.get(VogonEngine.PARAM_RUN_ID);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    static @Nullable String stringParam(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    static @Nullable Map<String, Object> mapParam(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof Map<?, ?> m
                ? m.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                e -> String.valueOf(e.getKey()), Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new))
                : null;
    }
}
