package de.mhus.vance.brain.script;

import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.brain.action.ScopeLevel;
import de.mhus.vance.brain.tools.ContextToolsApi;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * One script run. The {@link ContextToolsApi} carries the
 * scope-bound tool surface — the same one the engine's LLM tool
 * loop uses, so a script call goes through the same allow-filter,
 * permission and listener path.
 *
 * <p>{@code code} is the JavaScript source. {@code sourceName} shows
 * up in stack traces — pass the originating path or a stable label
 * to make script errors readable.
 *
 * <p>{@code bindings} are injected into the script's global scope
 * before evaluation: each entry becomes a top-level variable visible
 * to the script. Used by {@code scripted}-type server tools to pass
 * typed inputs (declared in the tool's parameters schema) through
 * verbatim — e.g. binding {@code {"a": 5, "b": 3}} makes the script
 * able to write {@code a + b}.
 */
public record ScriptRequest(
        String language,
        String code,
        @Nullable String sourceName,
        ContextToolsApi tools,
        Duration timeout,
        Map<String, @Nullable Object> bindings,
        @Nullable String recipeName,
        ScopeLevel scopeLevel,
        @Nullable BiConsumer<String, @Nullable Map<String, Object>> progressEmitter,
        @Nullable BiConsumer<String, @Nullable NotificationSeverity> notificationEmitter) {

    public ScriptRequest {
        if (!"js".equals(language)) {
            throw new IllegalArgumentException(
                    "Unsupported script language: " + language + " (only 'js' is allowed in v1)");
        }
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must not be empty");
        }
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (bindings == null) {
            throw new IllegalArgumentException("bindings must not be null (use Map.of() instead)");
        }
        if (bindings.containsKey("vance")) {
            throw new IllegalArgumentException(
                    "binding name 'vance' is reserved for the host API");
        }
        if (scopeLevel == null) {
            throw new IllegalArgumentException("scopeLevel must not be null");
        }
    }

    /**
     * Convenience constructor for the historical 5-argument shape — no
     * bindings beyond the {@code vance} host object, no recipe name,
     * defaults to {@link ScopeLevel#PROCESS_SCOPED}, no progress emitter.
     */
    public ScriptRequest(
            String language,
            String code,
            @Nullable String sourceName,
            ContextToolsApi tools,
            Duration timeout) {
        this(language, code, sourceName, tools, timeout, Map.of(), null,
                ScopeLevel.PROCESS_SCOPED, null, null);
    }

    /**
     * Convenience constructor for the historical 6-argument shape —
     * bindings supplied, no recipe name. Defaults to
     * {@link ScopeLevel#PROCESS_SCOPED}, no progress emitter.
     */
    public ScriptRequest(
            String language,
            String code,
            @Nullable String sourceName,
            ContextToolsApi tools,
            Duration timeout,
            Map<String, @Nullable Object> bindings) {
        this(language, code, sourceName, tools, timeout, bindings, null,
                ScopeLevel.PROCESS_SCOPED, null, null);
    }

    /**
     * Convenience constructor for callers that supply a recipe name but
     * want the default {@link ScopeLevel#PROCESS_SCOPED}. No progress
     * emitter — most non-Hactar callers don't have a parent process to
     * emit progress for.
     */
    public ScriptRequest(
            String language,
            String code,
            @Nullable String sourceName,
            ContextToolsApi tools,
            Duration timeout,
            Map<String, @Nullable Object> bindings,
            @Nullable String recipeName) {
        this(language, code, sourceName, tools, timeout, bindings, recipeName,
                ScopeLevel.PROCESS_SCOPED, null, null);
    }

    /**
     * 8-argument convenience for callers that supply scopeLevel but no
     * progress emitter (the most common case for trigger-scoped script
     * runs — see {@link de.mhus.vance.brain.action.ScriptActionExecutor}).
     */
    public ScriptRequest(
            String language,
            String code,
            @Nullable String sourceName,
            ContextToolsApi tools,
            Duration timeout,
            Map<String, @Nullable Object> bindings,
            @Nullable String recipeName,
            ScopeLevel scopeLevel) {
        this(language, code, sourceName, tools, timeout, bindings, recipeName,
                scopeLevel, null, null);
    }

    /**
     * 9-argument convenience for callers that already supply a progress
     * emitter (Hactar's ExecutingPhase used to live here before the
     * notification bridge was added). Defaults {@code notificationEmitter}
     * to {@code null} — call sites that want notifications wire it via
     * the full canonical constructor.
     */
    public ScriptRequest(
            String language,
            String code,
            @Nullable String sourceName,
            ContextToolsApi tools,
            Duration timeout,
            Map<String, @Nullable Object> bindings,
            @Nullable String recipeName,
            ScopeLevel scopeLevel,
            @Nullable BiConsumer<String, @Nullable Map<String, Object>> progressEmitter) {
        this(language, code, sourceName, tools, timeout, bindings, recipeName,
                scopeLevel, progressEmitter, null);
    }
}
