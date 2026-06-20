package de.mhus.vance.brain.tools.process;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spawn a worker process, drive its lane synchronously to completion of
 * one turn, and return the worker's last ASSISTANT reply. Sibling to
 * {@code ProcessCreateTool} but with explicit synchronous semantics —
 * blocks until the worker finishes a turn (or the timeout trips), then
 * returns the captured reply.
 *
 * <p>Same pattern Vogon uses internally
 * ({@code VogonEngine.spawnAndAwaitWorker}): spawn via the central
 * {@link ActionExecutorRegistry}, lane-scheduled steer, read the last
 * ASSISTANT text, stop. Exposing it as a tool lets skill-bound scripts
 * orchestrate per-chapter / per-step sub-workers without the engine
 * being JS-aware.
 *
 * <p>The caller passes {@code recipe} for cascade resolution; blank
 * defaults to the bundled {@code "default"} recipe. Steer content is
 * what the worker receives as its first user-message; if a worker
 * recipe expects a structured input shape, the caller fills it in here.
 *
 * <p>Timeout protects against worker turns that hang on a slow external
 * call. Default 300 s; configurable per call up to {@link #MAX_TIMEOUT}.
 * The script engine's own wall-clock timeout still applies on top.
 *
 * <p>Connection-profile is inherited from the calling process so a worker
 * spawned by a Foot-connected Arthur picks up the foot profile-block —
 * same semantics as {@code ProcessCreateTool}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@de.mhus.vance.toolpack.SpawnTool
public class ProcessRunTool implements Tool {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(15);

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "Stable name for the spawned worker process. "
                        + "Used in logs and the worker's process row."));
        properties.put("goal", Map.of(
                "type", "string",
                "description", "Short one-line description of what the "
                        + "worker is supposed to do — shown in process "
                        + "listings."));
        properties.put("recipe", Map.of(
                "type", "string",
                "description", "Recipe name (e.g. 'ford', 'analyze'). "
                        + "Resolved through the cascade; defaults to "
                        + "the bundled 'default' recipe when omitted."));
        properties.put("params", Map.of(
                "type", "object",
                "description", "Engine-specific params merged over "
                        + "recipe defaults.",
                "additionalProperties", true));
        properties.put("steerContent", Map.of(
                "type", "string",
                "description", "The user-message sent to the worker. "
                        + "This is what the worker's LLM sees as the "
                        + "task. Required for Ford-style workers."));
        properties.put("timeoutSeconds", Map.of(
                "type", "integer",
                "description", "Per-call wall-clock timeout (default "
                        + DEFAULT_TIMEOUT.toSeconds() + "s, max "
                        + MAX_TIMEOUT.toSeconds() + "s)."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("name", "goal", "steerContent"));
    }

    private final ActionExecutorRegistry actionRegistry;
    private final ThinkProcessService thinkProcessService;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final LaneScheduler laneScheduler;
    private final ChatMessageService chatMessageService;

    @Override
    public String name() {
        return "process_run";
    }

    @Override
    public String description() {
        return "Spawn a worker process, drive its lane synchronously to "
                + "completion of one turn, and return the worker's last "
                + "ASSISTANT reply. Use this when you orchestrate sub-"
                + "workers from a skill-bound script and need each reply "
                + "before starting the next one. Pass the recipe name "
                + "and steerContent as the worker's user-message, get "
                + "back {processId, status, reply}.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String name = requireString(params, "name");
        String goal = requireString(params, "goal");
        String steerContent = requireString(params, "steerContent");
        String recipeName = optString(params, "recipe");
        Map<String, Object> callerParams = optMap(params, "params");
        Duration timeout = resolveTimeout(params);

        if (ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            throw new ToolException(
                    "process_run must be invoked from a process with a session");
        }

        // ── Spawn via central pipeline ───────────────────────────────────
        String parentProfile = parentConnectionProfile(ctx.processId());
        TriggerAction.Recipe action = new TriggerAction.Recipe(
                recipeName,
                name,
                /*title*/ null,
                goal,
                /*inheritContextLevel*/ null,
                parentProfile,
                /*initialMessage*/ null,  // sync-wait drives the steer below
                callerParams,
                /*runAs*/ null);
        TriggerContext triggerCtx = TriggerContext.sessioned(
                ctx.tenantId(), ctx.projectId(),
                /*resolvedRunAs*/ null, /*correlationId*/ null,
                /*sourceTag*/ "tool:process_run",
                ctx.sessionId(), ctx.processId());

        ActionResult result = actionRegistry.execute(action, triggerCtx, TriggerKind.TOOL);
        if (result.outcome() != ActionOutcome.SCHEDULED) {
            throw new ToolException(
                    "process_run: spawn failed (" + result.outcome() + "): "
                            + result.errorMessage());
        }
        String childId = result.spawnedId();
        ThinkProcessDocument child = thinkProcessService.findById(childId)
                .orElseThrow(() -> new ToolException(
                        "process_run: spawned process '" + childId + "' is gone"));

        log.info("process_run spawned child='{}' name='{}' engine='{}' recipe='{}' timeoutSec={}",
                child.getId(), name, child.getThinkEngine(),
                child.getRecipeName() == null ? "(none)" : child.getRecipeName(),
                timeout.toSeconds());

        // ── Synchronous lane-wait ────────────────────────────────────────
        ThinkEngineService engineService = thinkEngineServiceProvider.getObject();
        @Nullable String reply = null;
        String terminalStatus;
        try {
            SteerMessage.UserChatInput message = new SteerMessage.UserChatInput(
                    Instant.now(),
                    /*idempotencyKey*/ null,
                    "process_run:" + (ctx.processId() == null ? "anon" : ctx.processId()),
                    steerContent);
            try {
                laneScheduler.submit(child.getId(),
                                () -> engineService.steer(child, message))
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                throw new ToolException(
                        "process_run: worker '" + child.getId() + "' didn't complete "
                                + "within " + timeout.toSeconds() + "s", te);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ToolException(
                        "process_run interrupted waiting for child='" + child.getId() + "'", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                throw new ToolException(
                        "process_run: worker turn failed for child='" + child.getId()
                                + "': " + cause.getMessage(), cause);
            }
            reply = readLastAssistantText(
                    child.getTenantId(), child.getSessionId(), child.getId());
            ThinkProcessDocument refreshed = thinkProcessService.findById(child.getId())
                    .orElse(child);
            terminalStatus = refreshed.getStatus() == null
                    ? "UNKNOWN" : refreshed.getStatus().name();
        } finally {
            try {
                engineService.stop(child);
            } catch (RuntimeException e) {
                log.warn("process_run: stop failed for child='{}': {}",
                        child.getId(), e.toString());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processId", child.getId());
        out.put("status", terminalStatus);
        out.put("engine", child.getThinkEngine());
        if (child.getRecipeName() != null) {
            out.put("recipe", child.getRecipeName());
        }
        if (reply != null) {
            out.put("reply", reply);
        }
        return out;
    }

    /** Q11 fix: inherit the parent process's connection profile when present. */
    private @Nullable String parentConnectionProfile(@Nullable String parentProcessId) {
        if (parentProcessId == null || parentProcessId.isBlank()) return null;
        return thinkProcessService.findById(parentProcessId)
                .map(ThinkProcessDocument::getConnectionProfile)
                .orElse(null);
    }

    private @Nullable String readLastAssistantText(
            String tenantId, String sessionId, String workerProcessId) {
        List<ChatMessageDocument> history = chatMessageService.history(
                tenantId, sessionId, workerProcessId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDocument m = history.get(i);
            if (m.getRole() == ChatRole.ASSISTANT && m.getContent() != null
                    && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return null;
    }

    private static Duration resolveTimeout(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("timeoutSeconds");
        if (raw instanceof Number n) {
            long s = Math.max(1, Math.min(MAX_TIMEOUT.toSeconds(), n.longValue()));
            return Duration.ofSeconds(s);
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                long parsed = Long.parseLong(s.trim());
                long clamped = Math.max(1, Math.min(MAX_TIMEOUT.toSeconds(), parsed));
                return Duration.ofSeconds(clamped);
            } catch (NumberFormatException ignore) {
                // fall through to default
            }
        }
        return DEFAULT_TIMEOUT;
    }

    private static String requireString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException(
                    "'" + key + "' is required and must be a non-empty string");
        }
        return s.trim();
    }

    private static @Nullable String optString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> optMap(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
