package de.mhus.vance.brain.hactar.phases;

import static de.mhus.vance.brain.hactar.phases.HactarContextRenderer.scriptAllowedTools;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * EXECUTING — actually runs the validated script via
 * {@link ScriptExecutor}. Builds a {@link ContextToolsApi} narrowed
 * to {@code scriptAllowedTools} so the script can only call tools
 * the caller explicitly approved.
 *
 * <p>On success the return value + duration are stored on state →
 * DONE. On {@link ScriptExecutionException} the error message +
 * class are recorded → FAILED. Runtime errors do <em>not</em> feed
 * back into the DRAFTING recovery loop (DRAFTING corrects syntax,
 * not runtime semantics).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutingPhase {

    /** Engine-param key for the script's input bindings map. */
    public static final String SCRIPT_ARGS_KEY = "scriptArgs";

    /** Engine-param key for the EXECUTING-phase fallback timeout. */
    public static final String EXECUTION_TIMEOUT_KEY = "executionTimeoutSeconds";

    private final ScriptExecutor scriptExecutor;
    private final ToolDispatcher toolDispatcher;

    public HactarStatus execute(
            HactarState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String code = state.getGeneratedCode();
        if (code == null || code.isBlank()) {
            state.setFailureReason(
                    "EXECUTING entered with empty generatedCode — "
                            + "DRAFTING/VALIDATING must run first");
            return HactarStatus.FAILED;
        }

        ToolInvocationContext scope = new ToolInvocationContext(
                process.getTenantId(),
                process.getProjectId(),
                process.getSessionId(),
                process.getId(),
                /*userId*/ null);
        Set<String> scriptTools = new LinkedHashSet<>(scriptAllowedTools(process));
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope, scriptTools);

        Map<String, @Nullable Object> bindings = scriptArgsBindings(process);
        Duration timeout = executionTimeout(process);

        try {
            // 7-arg ScriptRequest: pass the recipe name so the script
            // sees it on vance.context.recipe alongside the existing
            // tenant/project/session/process/user fields.
            ScriptResult result = scriptExecutor.run(
                    new ScriptRequest(
                            "js", code, "hactar:" + process.getId(),
                            tools, timeout, bindings, process.getRecipeName()));
            state.setExecutionResult(result.value());
            state.setExecutionDurationMs(result.duration().toMillis());
            state.setExecutionError(null);
            state.setExecutionErrorClass(null);
            log.info("Hactar.runExecuting id='{}' OK — duration={}ms, valueClass={}",
                    process.getId(), result.duration().toMillis(),
                    result.value() == null ? "null"
                            : result.value().getClass().getSimpleName());
            return HactarStatus.DONE;
        } catch (ScriptExecutionException e) {
            state.setExecutionError(e.getMessage());
            state.setExecutionErrorClass(e.errorClass().name());
            state.setExecutionResult(null);
            state.setFailureReason("Script execution failed ("
                    + e.errorClass().name() + "): " + e.getMessage());
            log.warn("Hactar.runExecuting id='{}' FAIL class={} msg={}",
                    process.getId(), e.errorClass(), e.getMessage());
            return HactarStatus.FAILED;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, @Nullable Object> scriptArgsBindings(
            ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(SCRIPT_ARGS_KEY);
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of("args", new LinkedHashMap<String, Object>());
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String key && !"vance".equals(key)) {
                args.put(key, e.getValue());
            }
        }
        return Map.of("args", args);
    }

    private static Duration executionTimeout(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(EXECUTION_TIMEOUT_KEY);
        if (raw instanceof Number n && n.longValue() > 0) {
            return Duration.ofSeconds(n.longValue());
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                if (s.matches("\\d+")) {
                    return Duration.ofSeconds(Long.parseLong(s.trim()));
                }
                return Duration.parse("PT" + s.trim().toUpperCase());
            } catch (RuntimeException e) {
                // Fall through to default.
            }
        }
        return Duration.ofMinutes(5);
    }
}
