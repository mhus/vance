package de.mhus.vance.brain.action;

import de.mhus.vance.brain.script.ScriptExecutionException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps a script's return value (or {@link ScriptExecutionException})
 * onto an {@link ActionResult}. The mapping rules live in
 * {@code specification/trigger-actions.md} §5.3 — permissive-structured: the
 * explicit {@code {success: bool}} wrapper signals the outcome, plain
 * returns count as {@code success}, only kaputt returns map to
 * {@code technical_error}.
 *
 * <p>Stateless. Static methods, no fields.
 */
public final class ScriptOutcomeMapper {

    private ScriptOutcomeMapper() {}

    /**
     * Map a successful {@code ScriptExecutor} return value to an
     * {@link ActionResult}.
     *
     * @param value the raw return from the script (Polyglot-mapped:
     *              primitives stay primitives, JS objects → Maps,
     *              JS arrays → Lists, {@code null} for void/undefined).
     */
    public static ActionResult mapValue(@Nullable Object value) {
        // null / undefined / void
        if (value == null) {
            return ActionResult.success(Map.of());
        }
        // Object (Map): apply wrapper-pattern detection
        if (value instanceof Map<?, ?> rawMap) {
            return mapObjectReturn(rawMap);
        }
        // Array (List)
        if (value instanceof List<?> list) {
            return ActionResult.success(Map.of("value", list));
        }
        // Primitive: String, Number (Integer/Long/Double/...), Boolean
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return ActionResult.success(Map.of("value", value));
        }
        // Anything else (Function, Promise, foreign host object) is not
        // serialisable for downstream consumers — reject explicitly so
        // workflow `catch:` blocks can route it cleanly.
        return ActionResult.failure(
                ActionOutcome.TECHNICAL_ERROR,
                "non-serializable script return type: " + value.getClass().getName(),
                Map.of("error",
                        "non-serializable-return:" + value.getClass().getSimpleName()));
    }

    /**
     * Map a {@link ScriptExecutionException} thrown by the
     * {@code ScriptExecutor} onto an {@link ActionResult}. Error-class
     * distinguishes user-domain exceptions (guest throws) from
     * sandbox-level failures (resource exhaustion, host exceptions).
     */
    public static ActionResult mapException(ScriptExecutionException e) {
        return switch (e.errorClass()) {
            case GUEST_EXCEPTION -> ActionResult.failure(
                    ActionOutcome.BUSINESS_ERROR,
                    e.getMessage(),
                    Map.of("error", safe(e.getMessage())));
            case TIMEOUT -> ActionResult.failure(
                    ActionOutcome.TIMEOUT,
                    "script wall-clock timeout",
                    Map.of("error", "timeout"));
            case RESOURCE_EXHAUSTED -> ActionResult.failure(
                    ActionOutcome.TECHNICAL_ERROR,
                    "script resource limit exceeded: " + e.getMessage(),
                    Map.of("error", "resource_exhausted"));
            case CANCELLED -> ActionResult.failure(
                    ActionOutcome.CANCELLED,
                    "script cancelled",
                    Map.of("error", "cancelled"));
            case HOST_EXCEPTION, INVALID_HEADER, MISSING_CAPABILITY -> ActionResult.failure(
                    ActionOutcome.TECHNICAL_ERROR,
                    e.errorClass().name() + ": " + e.getMessage(),
                    Map.of("error", e.errorClass().name().toLowerCase()));
        };
    }

    /**
     * Sentinel for "the script source could not be loaded" — used by the
     * executor before it ever calls into the {@code ScriptExecutor}.
     */
    public static ActionResult scriptNotFound(String path) {
        return ActionResult.failure(
                ActionOutcome.TECHNICAL_ERROR,
                "script not found: " + path,
                Map.of("error", "script-not-found:" + path));
    }

    // ──────────────────── Internals ────────────────────

    private static ActionResult mapObjectReturn(Map<?, ?> rawMap) {
        // Disallow non-string keys before doing anything else — keeps
        // the downstream output strictly JSON-shaped.
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawMap.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                return ActionResult.failure(
                        ActionOutcome.TECHNICAL_ERROR,
                        "non-string key in script return: " + e.getKey(),
                        Map.of("error", "non-string-key"));
            }
            map.put(key, e.getValue());
        }
        if (!map.containsKey("success")) {
            // No wrapper-pattern — return the whole object as success.
            return ActionResult.success(map);
        }
        Object successVal = map.get("success");
        if (!(successVal instanceof Boolean successBool)) {
            return ActionResult.failure(
                    ActionOutcome.TECHNICAL_ERROR,
                    "script return has non-boolean 'success': " + successVal,
                    Map.of("error", "invalid-success-type"));
        }
        // Strip the success-key from the payload; downstream sees just
        // the domain-specific fields.
        Map<String, Object> payload = new LinkedHashMap<>(map);
        payload.remove("success");
        if (successBool) {
            return ActionResult.success(payload);
        }
        return ActionResult.failure(
                ActionOutcome.BUSINESS_ERROR,
                payload.containsKey("error") ? String.valueOf(payload.get("error"))
                        : "script returned success:false",
                payload);
    }

    private static String safe(@Nullable String s) {
        return s == null ? "" : s;
    }
}
