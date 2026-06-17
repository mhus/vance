package de.mhus.vance.brain.hactar.phases;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * EXECUTING — runs the validated script via {@link ScriptExecutor}.
 * Builds a {@link ContextToolsApi} narrowed to the engine-param
 * {@code scriptAllowedTools} so the script can only call tools the
 * caller explicitly approved.
 *
 * <p>On success the return value + duration are stored on state →
 * DONE. On {@link ScriptExecutionException} the error message +
 * class are recorded → FAILED. Runtime errors are <em>terminal</em>
 * — Hactar v2 doesn't author, so there's no recovery loop. The
 * caller's next step on FAILED is to spawn Slart with
 * {@code mode=UPDATE + existingScriptRef + failureReason} (manual,
 * per planning §6.3).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutingPhase {

    /** Engine-param key for the script's input bindings map.
     *  Renamed from the legacy {@code scriptArgs} per
     *  planning §5.2. */
    public static final String SCRIPT_PARAMS_KEY = "scriptParams";

    /** Engine-param key for the EXECUTING-phase fallback timeout.
     *  Accepts seconds (int) or ISO-8601 duration suffix
     *  ({@code 30s}, {@code 5m}, {@code 1h}). Renamed from the
     *  legacy {@code executionTimeoutSeconds}. */
    public static final String TIMEOUT_KEY = "timeout";

    private final ScriptExecutor scriptExecutor;
    private final ToolDispatcher toolDispatcher;
    private final ProgressEmitter progressEmitter;
    private final NotificationService notificationService;
    private final SessionService sessionService;

    public HactarStatus execute(
            HactarState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String code = state.getScriptBody();
        if (code == null || code.isBlank()) {
            state.setFailureReason(
                    "EXECUTING entered with empty scriptBody — "
                            + "LOADING must run first");
            return HactarStatus.FAILED;
        }

        // Resolve the session owner so user-scope settings-resolver
        // lookups ({{secret:user:...}}) work for scheduler-driven runs
        // and other system-session contexts. The cortex-run path takes
        // userId from the HTTP-JWT; here we read it from the session
        // the think-process belongs to. Without this, IMAP/OAuth tools
        // bound to per-user credentials silently see an empty resolver
        // substitution and fail with cryptic provider errors.
        String sessionOwner = process.getSessionId() == null ? null
                : sessionService.findBySessionId(process.getSessionId())
                        .map(SessionDocument::getUserId)
                        .orElse(null);
        ToolInvocationContext scope = new ToolInvocationContext(
                process.getTenantId(),
                process.getProjectId(),
                process.getSessionId(),
                process.getId(),
                sessionOwner);
        Set<String> scriptTools = LoadingPhase.scriptAllowedTools(process);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope, scriptTools);

        Map<String, @Nullable Object> bindings = scriptParamsBindings(process);
        Duration timeout = executionTimeout(process);
        String sourceName = state.getScriptRef() == null
                ? "hactar:" + process.getId()
                : state.getScriptRef();

        // vance.process.progress(...) — emits live status pings on the
        // Hactar process so the user sees iteration progress without
        // scraping logs. Bound here because the emitter needs both the
        // ProgressEmitter bean and the running process document.
        // SCRIPT_PROGRESS (not INFO) — the script author called this
        // explicitly, so it must pass the NORMAL progress-level filter
        // by default. Payload Map flattens into the status `detail`
        // field as a compact "k=v, k=v" string.
        BiConsumer<String, @Nullable Map<String, Object>> progressBridge =
                (message, payload) -> {
                    StatusPayload.StatusPayloadBuilder builder =
                            StatusPayload.builder()
                                    .tag(StatusTag.SCRIPT_PROGRESS)
                                    .text(message);
                    if (payload != null && !payload.isEmpty()) {
                        builder.detail(formatPayload(payload));
                    }
                    progressEmitter.emitStatus(process, builder.build());
                };

        // vance.process.notify(...) — fires a NOTIFY frame so the user's
        // client beeps / shows a toast / fires a system notification.
        // Wired to NotificationService, which routes session-bound and
        // drops on the floor when no client is connected (spec §4).
        BiConsumer<String, @Nullable NotificationSeverity> notificationBridge =
                (message, severity) ->
                        notificationService.publish(process, message, severity);

        try {
            ScriptResult result = scriptExecutor.run(
                    new ScriptRequest(
                            state.getLanguage() == null ? "js" : state.getLanguage(),
                            code, sourceName,
                            tools, timeout, bindings, process.getRecipeName(),
                            de.mhus.vance.brain.action.ScopeLevel.PROCESS_SCOPED,
                            progressBridge, notificationBridge));
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
    private static Map<String, @Nullable Object> scriptParamsBindings(
            ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(SCRIPT_PARAMS_KEY);
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

    /**
     * Flatten a script-supplied payload map into a compact "k=v, k=v"
     * string for the {@link StatusPayload#getDetail()} field. Trims
     * after 500 chars so a misbehaving script can't ship a 1MB string
     * through the status channel.
     */
    private static String formatPayload(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
            if (sb.length() > 500) {
                sb.setLength(497);
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }

    private static Duration executionTimeout(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(TIMEOUT_KEY);
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
