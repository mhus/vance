package de.mhus.vance.brain.tools.process;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.api.action.TriggerKind;
import de.mhus.vance.brain.delegate.RecipeSelectorService;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * The single spawn tool — creates a worker think-process in the current
 * session and runs it on its {@code task}. Consolidates the former
 * {@code process_create} (async fire-and-forget) and {@code process_run}
 * (synchronous spawn-and-wait) into one surface distinguished only by the
 * {@code wait} flag.
 *
 * <p><b>Task always runs.</b> {@code task} is both the process's stored
 * goal <em>and</em> the first {@code USER_CHAT_INPUT} pushed into its
 * pending queue — so a spawned worker never idles waiting for a separate
 * steer (the trap the old {@code goal}-required-but-{@code steerContent}-
 * optional split created). Aliases {@code goal} / {@code prompt} /
 * {@code steerContent} are tolerated for weak tool-use models.
 *
 * <p><b>Routing.</b> Pass {@code recipe} for direct cascade resolution
 * (unknown names fail strict with a suggestion list). Omit it (or
 * {@code "auto"}) to let the trigger-gated {@link RecipeSelectorService}
 * pick from {@code task}; on NONE the tenant fallback recipe
 * ({@code routing.fallback.recipe}, default {@code slart-and-run}) or the
 * bundled {@code default} recipe is spawned.
 *
 * <p><b>wait.</b> {@code false} (default) — async: return immediately with
 * spawn metadata; the worker runs on its own lane and reports its terminal
 * state back to the parent via {@code ProcessEvent}. {@code true} — drive
 * the worker's lane synchronously to the end of one turn and return its
 * last ASSISTANT reply under {@code reply}. Use {@code wait=true} when a
 * skill-bound script orchestrates sub-workers and needs each reply before
 * starting the next one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@de.mhus.vance.toolpack.SpawnTool
public class ProcessSpawnTool implements Tool {

    /** Value treated as "no explicit recipe" — same as omitting it. */
    public static final String RECIPE_AUTO = "auto";

    /** Tenant-overridable recipe spawned when the selector returns NONE
     *  after a trigger fired. Empty value disables the fallback. */
    static final String SETTING_FALLBACK_RECIPE = "routing.fallback.recipe";

    /** Default value for {@link #SETTING_FALLBACK_RECIPE}. */
    static final String DEFAULT_FALLBACK_RECIPE = "slart-and-run";

    /** Recipe used when the selector returns NONE and no trigger fired. */
    static final String DEFAULT_RECIPE = "default";

    /** Synchronous ({@code wait=true}) lane-wait bounds. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(15);

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "Stable process name, unique per session."));
        properties.put("task", Map.of(
                "type", "string",
                "description", "What the worker should do. This is both the "
                        + "process's goal AND its first user-message — the "
                        + "worker starts working on it immediately (no "
                        + "separate steer needed). When `recipe` is omitted "
                        + "the selector also routes from this text, so be "
                        + "specific: 'write a research report on gRPC vs REST' "
                        + "beats 'do something with research'. Aliases `goal` / "
                        + "`prompt` / `steerContent` are accepted."));
        properties.put("recipe", Map.of(
                "type", "string",
                "description", "Preferred routing: recipe name for cascade "
                        + "resolution. Empty / null / 'auto' → the selector "
                        + "picks a recipe from `task`. Unknown names fail "
                        + "strict with a suggestion list — consult "
                        + "`recipe_list` if unsure."));
        properties.put("wait", Map.of(
                "type", "boolean",
                "description", "false (default) → async: return spawn "
                        + "metadata immediately; the worker runs on its own "
                        + "and reports back when done. true → block until the "
                        + "worker finishes one turn and return its reply under "
                        + "`reply`. Use true only for tight script "
                        + "orchestration that needs each reply inline."));
        properties.put("title", Map.of(
                "type", "string",
                "description", "Optional human-readable title."));
        properties.put("params", Map.of(
                "type", "object",
                "description", "Engine-specific runtime parameters (model, "
                        + "validation, maxIterations, …), override recipe "
                        + "defaults per-key.",
                "additionalProperties", true));
        properties.put("fallbackOnNone", Map.of(
                "type", "boolean",
                "description", "Selector-routed mode only: when the selector "
                        + "returns NONE, spawn the tenant fallback recipe "
                        + "(default slart-and-run). Default true."));
        properties.put("timeoutSeconds", Map.of(
                "type", "integer",
                "description", "wait=true only: per-call wall-clock timeout "
                        + "(default " + DEFAULT_TIMEOUT.toSeconds() + "s, max "
                        + MAX_TIMEOUT.toSeconds() + "s)."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("name", "task"));
    }

    private final ActionExecutorRegistry actionRegistry;
    private final ThinkProcessService thinkProcessService;
    private final RecipeSelectorService selector;
    private final SettingService settingService;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final LaneScheduler laneScheduler;
    private final ChatMessageService chatMessageService;

    @Override
    public String name() {
        return "process_spawn";
    }

    @Override
    public String description() {
        return "Spawn a worker process in the current session and run it on "
                + "its `task`. The worker starts working immediately — `task` "
                + "is both its goal and its first message. Routing: pass "
                + "`recipe` for a known recipe (unknown → suggestion list), or "
                + "omit it so the trigger-gated selector picks from `task` "
                + "(NONE → tenant fallback recipe). `wait=false` (default) "
                + "returns spawn metadata and the worker reports back "
                + "asynchronously; `wait=true` blocks for one turn and returns "
                + "the worker's reply under `reply` (for tight script "
                + "orchestration). Selector mode returns {decision, recipe, "
                + "engine, rationale, fallback?, process?}.";
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
        if (ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            throw new ToolException("process_spawn requires a session scope");
        }
        String name = stringOrThrow(params, "name");
        String task = resolveTask(params, name);
        String recipeName = normaliseRecipeParam(optString(params, "recipe"));
        String title = optString(params, "title");
        boolean wait = optBoolean(params, "wait", false);
        Map<String, Object> callerParams = optMap(params, "params");
        boolean fallbackOnNone = optBoolean(params, "fallbackOnNone", true);
        Duration timeout = resolveTimeout(params);

        // ── Selector-routed mode (decide recipe before dispatch) ─────────
        if (recipeName == null) {
            return invokeSelectorRouted(
                    ctx, name, task, title, callerParams, fallbackOnNone, wait, timeout);
        }

        // ── Explicit recipe dispatch ─────────────────────────────────────
        return dispatch(ctx, name, title, task, recipeName, callerParams, wait, timeout);
    }

    private Map<String, Object> invokeSelectorRouted(
            ToolInvocationContext ctx, String name, String task,
            @Nullable String title, @Nullable Map<String, Object> callerParams,
            boolean fallbackOnNone, boolean wait, Duration timeout) {
        ThinkProcessDocument caller = resolveCaller(ctx);
        RecipeSelectorService.Result result = selector.select(caller, task);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", result.decision().name());
        out.put("recipe", result.recipeName());
        out.put("engine", result.engineName());
        out.put("rationale", result.rationale());

        if (result.decision() == RecipeSelectorService.Result.Decision.MATCH) {
            Map<String, Object> spawn = dispatch(ctx, name, title, task,
                    result.recipeName(), callerParams, wait, timeout);
            out.put("process", spawn);
            log.info("process_spawn name='{}' → spawned recipe='{}' via selector",
                    name, result.recipeName());
            return out;
        }

        if (!fallbackOnNone) {
            log.info("process_spawn name='{}' task='{}' → NONE (fallbackOnNone=false): {}",
                    name, abbrev(task, 80), result.rationale());
            return out;
        }

        if (!result.triggerObserved()) {
            log.info("process_spawn name='{}' → NONE without trigger, "
                            + "falling through to default recipe (rationale: {})",
                    name, result.rationale());
            Map<String, Object> fallbackInfo = new LinkedHashMap<>();
            fallbackInfo.put("recipe", DEFAULT_RECIPE);
            fallbackInfo.put("source", "default-recipe (no trigger)");
            out.put("fallback", fallbackInfo);
            Map<String, Object> spawn = dispatch(ctx, name, title, task,
                    DEFAULT_RECIPE, callerParams, wait, timeout);
            out.put("process", spawn);
            return out;
        }

        String fallbackRecipe = resolveFallbackRecipe(ctx);
        if (fallbackRecipe == null) {
            log.info("process_spawn name='{}' → NONE after trigger, "
                            + "fallback disabled by setting: {}",
                    name, result.rationale());
            return out;
        }
        log.info("process_spawn name='{}' → NONE after trigger, "
                        + "spawning fallback recipe '{}' (rationale: {})",
                name, fallbackRecipe, result.rationale());
        Map<String, Object> fallbackInfo = new LinkedHashMap<>();
        fallbackInfo.put("recipe", fallbackRecipe);
        fallbackInfo.put("source", SETTING_FALLBACK_RECIPE);
        out.put("fallback", fallbackInfo);

        Map<String, Object> spawn = dispatch(ctx, name, title, task,
                fallbackRecipe, callerParams, wait, timeout);
        out.put("process", spawn);
        return out;
    }

    /**
     * Dispatch a {@link TriggerAction.Recipe}. In async mode ({@code wait
     * =false}) {@code task} rides as the {@code initialMessage} so the
     * executor seeds the pending queue and the worker auto-runs. In sync
     * mode ({@code wait=true}) the initial message is left null and the
     * steer is driven explicitly on the child lane so we can capture the
     * reply.
     */
    private Map<String, Object> dispatch(
            ToolInvocationContext ctx, String name, @Nullable String title,
            String task, @Nullable String recipeName,
            @Nullable Map<String, Object> callerParams, boolean wait, Duration timeout) {
        String parentProfile = parentConnectionProfile(ctx.processId());
        TriggerAction.Recipe action = new TriggerAction.Recipe(
                recipeName,
                name,
                title,
                task,
                /*inheritContextLevel*/ null,  // executor reads from recipe.params
                parentProfile,
                /*initialMessage*/ wait ? null : task,
                callerParams,
                /*runAs*/ null);
        TriggerContext triggerCtx = TriggerContext.sessioned(
                ctx.tenantId(), ctx.projectId(),
                /*resolvedRunAs*/ null, /*correlationId*/ null,
                /*sourceTag*/ "tool:process_spawn",
                ctx.sessionId(), ctx.processId());

        ActionResult result = actionRegistry.execute(action, triggerCtx, TriggerKind.TOOL);
        if (!wait) {
            return mapAsyncResult(result, recipeName, ctx.tenantId(), ctx.projectId());
        }
        return runSync(ctx, name, task, timeout, result, recipeName);
    }

    // ── async (wait=false) ────────────────────────────────────────────────

    private Map<String, Object> mapAsyncResult(
            ActionResult result, @Nullable String requestedRecipe,
            String tenantId, @Nullable String projectId) {
        switch (result.outcome()) {
            case SCHEDULED -> {
                Map<String, Object> out = result.output();
                if (out == null) return Map.of("processId", result.spawnedId());
                return out;
            }
            case SUCCESS -> {
                // already_exists soft-success.
                Map<String, Object> out = result.output();
                return out != null ? out : Map.of("status", "already_exists");
            }
            case TECHNICAL_ERROR, BUSINESS_ERROR, TIMEOUT, PERMISSION_ERROR, CANCELLED -> {
                String msg = result.errorMessage() == null
                        ? "process_spawn failed" : result.errorMessage();
                Map<String, Object> output = result.output();
                if (output != null && isUnknownRecipeOutput(output)) {
                    throw new ToolException(composeUnknownRecipeMessage(requestedRecipe, output));
                }
                throw new ToolException("process_spawn: " + msg);
            }
        }
        throw new ToolException("process_spawn: unexpected outcome " + result.outcome());
    }

    // ── sync (wait=true) ──────────────────────────────────────────────────

    private Map<String, Object> runSync(
            ToolInvocationContext ctx, String name, String task, Duration timeout,
            ActionResult result, @Nullable String requestedRecipe) {
        String childId;
        if (result.outcome() == ActionOutcome.SCHEDULED) {
            childId = result.spawnedId();
        } else if (result.outcome() == ActionOutcome.SUCCESS) {
            // Idempotent soft-success: reuse the existing process for the steer.
            Map<String, Object> out = result.output();
            Object existing = out == null ? null : out.get("existingProcessId");
            if (!(existing instanceof String existingId) || existingId.isBlank()) {
                throw new ToolException(
                        "process_spawn: a process named '" + name + "' already "
                                + "exists in this session but its id is unavailable "
                                + "— steer it by name instead");
            }
            childId = existingId;
        } else {
            String msg = result.errorMessage() == null
                    ? "process_spawn failed" : result.errorMessage();
            Map<String, Object> output = result.output();
            if (output != null && isUnknownRecipeOutput(output)) {
                throw new ToolException(composeUnknownRecipeMessage(requestedRecipe, output));
            }
            throw new ToolException("process_spawn: spawn failed (" + result.outcome()
                    + "): " + msg);
        }
        ThinkProcessDocument child = thinkProcessService.findById(childId)
                .orElseThrow(() -> new ToolException(
                        "process_spawn: spawned process '" + childId + "' is gone"));

        log.info("process_spawn(wait) child='{}' name='{}' engine='{}' recipe='{}' timeoutSec={}",
                child.getId(), name, child.getThinkEngine(),
                child.getRecipeName() == null ? "(none)" : child.getRecipeName(),
                timeout.toSeconds());

        ThinkEngineService engineService = thinkEngineServiceProvider.getObject();
        @Nullable String reply = null;
        String terminalStatus;
        try {
            SteerMessage.UserChatInput message = new SteerMessage.UserChatInput(
                    Instant.now(),
                    /*idempotencyKey*/ null,
                    "process_spawn:" + (ctx.processId() == null ? "anon" : ctx.processId()),
                    task);
            try {
                laneScheduler.submit(child.getId(),
                                () -> engineService.steer(child, message))
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                throw new ToolException(
                        "process_spawn: worker '" + child.getId() + "' didn't complete "
                                + "within " + timeout.toSeconds() + "s", te);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ToolException(
                        "process_spawn interrupted waiting for child='" + child.getId() + "'", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                throw new ToolException(
                        "process_spawn: worker turn failed for child='" + child.getId()
                                + "': " + cause.getMessage(), cause);
            }
            reply = readLastAssistantText(
                    child.getTenantId(), child.getSessionId(), child.getId());
            ThinkProcessDocument refreshed = thinkProcessService.findById(child.getId())
                    .orElse(child);
            terminalStatus = refreshed.getStatus() == null
                    ? "UNKNOWN" : refreshed.getStatus().name();
        } finally {
            // Serialize the stop onto the CHILD lane (never off-lane).
            try {
                laneScheduler.submit(child.getId(),
                                () -> {
                                    engineService.stop(child);
                                    return null;
                                })
                        .get(30, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                log.warn("process_spawn: stop for child='{}' enqueued behind a still-running "
                        + "turn; it will run on-lane once the turn yields", child.getId());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                log.warn("process_spawn: stop failed for child='{}': {}",
                        child.getId(), cause.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processId", child.getId());
        out.put("status", terminalStatus);
        out.put("engine", child.getThinkEngine());
        if (child.getRecipeName() != null) out.put("recipe", child.getRecipeName());
        if (reply != null) out.put("reply", reply);
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

    // ── shared helpers ────────────────────────────────────────────────────

    /**
     * Detects the unknown-recipe soft-error shape structurally, via the marker
     * keys {@code SpawnActionExecutor.buildUnknownRecipeOutput} always sets
     * ({@code requested}/{@code suggestions}/{@code available}). Replaces the
     * previous case-sensitive string-match on the error message, which silently
     * failed against the capital-U {@code "Unknown recipe '…'"} message and left
     * {@link #composeUnknownRecipeMessage} unreachable.
     */
    private static boolean isUnknownRecipeOutput(Map<String, Object> output) {
        return output.containsKey("requested")
                && output.containsKey("suggestions")
                && output.containsKey("available");
    }

    private static String composeUnknownRecipeMessage(
            @Nullable String requested, Map<String, Object> output) {
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) output.getOrDefault("suggestions", List.of());
        @SuppressWarnings("unchecked")
        List<String> available = (List<String>) output.getOrDefault("available", List.of());
        StringBuilder sb = new StringBuilder()
                .append("Unknown recipe '").append(requested).append("'. ");
        if (available.isEmpty()) {
            sb.append("No recipes are loaded in this project — omit `recipe` to ")
                    .append("let the selector route from `task`.");
            return sb.toString();
        }
        if (!suggestions.isEmpty()) {
            sb.append("Did you mean: ").append(String.join(", ", suggestions)).append("? ");
        }
        sb.append("Available: ").append(String.join(", ", available))
                .append(". Use `recipe_list` for descriptions, or omit `recipe` ")
                .append("to let the selector pick from `task`.");
        return sb.toString();
    }

    private @Nullable String resolveFallbackRecipe(ToolInvocationContext ctx) {
        String configured = settingService.getStringValueCascade(
                ctx.tenantId(), ctx.projectId(), /*processId*/ null,
                SETTING_FALLBACK_RECIPE);
        if (configured == null) return DEFAULT_FALLBACK_RECIPE;
        String trimmed = configured.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed;
    }

    /**
     * Resolve the worker task from {@code task}, tolerating the {@code goal}
     * / {@code prompt} / {@code steerContent} aliases weak tool-use models
     * (and the older create/run schemas) tend to send.
     */
    private String resolveTask(Map<String, Object> params, String name) {
        String task = optString(params, "task");
        if (task == null) task = optString(params, "goal");
        if (task == null) task = optString(params, "prompt");
        if (task == null) task = optString(params, "steerContent");
        if (task == null) {
            throw new ToolException("'task' is required and must be a non-empty "
                    + "string describing what the spawned worker should do "
                    + "(it is both the goal and the worker's first message).");
        }
        return task;
    }

    private @Nullable String parentConnectionProfile(@Nullable String parentProcessId) {
        if (parentProcessId == null || parentProcessId.isBlank()) return null;
        return thinkProcessService.findById(parentProcessId)
                .map(ThinkProcessDocument::getConnectionProfile)
                .orElse(null);
    }

    private ThinkProcessDocument resolveCaller(ToolInvocationContext ctx) {
        String pid = ctx.processId();
        if (pid == null || pid.isBlank()) {
            throw new ToolException(
                    "process_spawn in selector-routed mode must be invoked from a "
                            + "running process (no processId in context)");
        }
        return thinkProcessService.findById(pid)
                .orElseThrow(() -> new ToolException(
                        "calling process '" + pid + "' not found"));
    }

    private static @Nullable String normaliseRecipeParam(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (RECIPE_AUTO.equalsIgnoreCase(trimmed)) return null;
        return trimmed;
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
                return Duration.ofSeconds(Math.max(1, Math.min(MAX_TIMEOUT.toSeconds(), parsed)));
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return DEFAULT_TIMEOUT;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static @Nullable String optString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static @Nullable Map<String, Object> optMap(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }

    private static boolean optBoolean(Map<String, Object> params, String key, boolean defaultValue) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            String trimmed = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(trimmed)) return true;
            if ("false".equals(trimmed)) return false;
        }
        return defaultValue;
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
