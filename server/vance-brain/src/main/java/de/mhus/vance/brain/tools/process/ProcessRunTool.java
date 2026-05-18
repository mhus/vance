package de.mhus.vance.brain.tools.process;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
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
 * Spawn a worker process, drive its lane synchronously to completion,
 * return the worker's last ASSISTANT reply. Sibling to
 * {@link ProcessCreateTool} but with explicit synchronous semantics —
 * blocks until the worker finishes a turn (or the timeout trips),
 * then returns the captured reply.
 *
 * <p>Same pattern Vogon uses internally
 * ({@code VogonEngine.spawnAndAwaitWorker}): create + start + lane-
 * scheduled steer + readLastAssistantText + stop. Exposing it as a
 * tool lets skill-bound scripts orchestrate per-chapter / per-step
 * sub-workers without the engine code being JS-aware.
 *
 * <p>Caller picks either {@code recipe} (resolved via recipe cascade)
 * or {@code engine} (direct engine name). Mutually exclusive — both
 * present is an error. Steer content is what the worker receives as
 * its first user-message; if a worker recipe expects a structured
 * input shape, the caller fills it in here.
 *
 * <p>Timeout protects against worker turns that hang on a slow
 * external call. Default 300 s; configurable per call. The script
 * engine's own wall-clock timeout still applies on top — if a single
 * worker call eats the whole 300 s, the script's overall budget
 * needs to be at least as long.
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
                "description", "Stable name for the spawned worker "
                        + "process. Used in logs and the worker's "
                        + "process row."));
        properties.put("goal", Map.of(
                "type", "string",
                "description", "Short one-line description of what "
                        + "the worker is supposed to do — shown in "
                        + "process listings."));
        properties.put("recipe", Map.of(
                "type", "string",
                "description", "Recipe name (e.g. 'ford', 'analyze'). "
                        + "Resolved through the cascade. Mutually "
                        + "exclusive with 'engine'."));
        properties.put("engine", Map.of(
                "type", "string",
                "description", "Direct engine name (e.g. 'ford'). "
                        + "Use when you don't want a recipe's "
                        + "defaults. Mutually exclusive with 'recipe'."));
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

    private final ThinkProcessService thinkProcessService;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final RecipeResolver recipeResolver;
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
                + "workers from a skill-bound script and need each "
                + "reply before starting the next one. Pick recipe OR "
                + "engine, pass steerContent as the worker's "
                + "user-message, get back {processId, status, reply}.";
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
        String engineName = optString(params, "engine");
        Map<String, Object> callerParams = optMap(params, "params");
        Duration timeout = resolveTimeout(params);

        if (recipeName != null && engineName != null) {
            throw new ToolException(
                    "process_run: pick exactly one of 'recipe' or 'engine'");
        }
        if (recipeName == null && engineName == null) {
            throw new ToolException(
                    "process_run: one of 'recipe' or 'engine' is required");
        }
        if (ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            throw new ToolException(
                    "process_run must be invoked from a process with a session");
        }

        ThinkEngineService engineService = thinkEngineServiceProvider.getObject();
        ThinkProcessDocument child;
        ThinkEngine resolvedEngine;
        @Nullable String resolvedRecipeName = null;

        if (recipeName != null) {
            AppliedRecipe applied;
            try {
                applied = recipeResolver.apply(
                        ctx.tenantId(), ctx.projectId(), recipeName,
                        /*connectionProfile*/ null, callerParams);
            } catch (RuntimeException e) {
                throw new ToolException(
                        "process_run: recipe '" + recipeName + "' failed to apply: "
                                + e.getMessage(), e);
            }
            resolvedEngine = engineService.resolve(applied.engine())
                    .orElseThrow(() -> new ToolException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
            resolvedRecipeName = applied.name();
            child = thinkProcessService.create(
                    ctx.tenantId(), ctx.projectId(), ctx.sessionId(),
                    name, resolvedEngine.name(), resolvedEngine.version(),
                    goal, steerContent, ctx.processId(),
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : Set.copyOf(applied.allowedSkills()));
        } else {
            resolvedEngine = engineService.resolve(engineName)
                    .orElseThrow(() -> new ToolException(
                            "Unknown engine '" + engineName + "' — known: "
                                    + engineService.listEngines()));
            child = thinkProcessService.create(
                    ctx.tenantId(), ctx.projectId(), ctx.sessionId(),
                    name, resolvedEngine.name(), resolvedEngine.version(),
                    goal, steerContent, ctx.processId(), callerParams);
        }

        engineService.start(child);
        log.info("process_run spawned child='{}' name='{}' engine='{}' recipe='{}' timeoutSec={}",
                child.getId(), name, resolvedEngine.name(),
                resolvedRecipeName == null ? "(none)" : resolvedRecipeName,
                timeout.toSeconds());

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
        out.put("engine", resolvedEngine.name());
        if (resolvedRecipeName != null) {
            out.put("recipe", resolvedRecipeName);
        }
        if (reply != null) {
            out.put("reply", reply);
        }
        return out;
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
