package de.mhus.vance.brain.action;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.inherit.ParentContextSpawnHelper;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spawn a ThinkProcess from a {@link TriggerAction.Recipe}. Single
 * spawn-surface for every caller outside the engine internals — scheduler,
 * event, workflow-task, LLM tool ({@code process_create}, {@code process_run}),
 * REST ({@code ScriptCortexController}), WebSocket
 * ({@code SessionBootstrapHandler}).
 *
 * <p>Recipe-driven only: cascade-resolved via
 * {@link RecipeResolver#applyDefaulting} (blank recipe defaults to
 * {@code "default"}). Unknown recipe names fail strict with a Levenshtein
 * suggestion list mounted under {@code output.suggestions} — caller decides
 * how to surface it.
 *
 * <p><b>Soft-failure: already-exists.</b> Returns
 * {@link ActionResult#success(Map)} (not failure) with
 * {@code output.status == "already_exists"} when a process with the
 * caller-supplied {@code processName} already exists. Idempotent — the
 * desired end state is reached.
 *
 * <p><b>Initial-message wrap.</b> When {@code action.initialMessage()} is
 * set and the spawn context has a parent process, the message is wrapped
 * via {@link ParentContextSpawnHelper} (inheriting the parent's chat
 * history) before being pushed as a {@code USER_CHAT_INPUT} pending
 * message.
 *
 * <p><b>Connection profile.</b> Explicit
 * {@code action.connectionProfile()} wins; otherwise derived from the
 * {@link TriggerKind} (scheduler/event/workflow/tool/user).
 *
 * <p>Returns {@link ActionResult#scheduled(String, Map)} with the spawned
 * process id and a {@code {processId, name, status, engine,
 * engineVersion, recipe?, steered?}} output map. The session id is
 * caller-supplied via {@link TriggerContext#parentSessionId()} — see
 * {@code planning/process-spawn-rewrite.md} §10.3.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class SpawnActionExecutor implements ActionExecutor<TriggerAction.Recipe> {

    /** Edit-distance cutoff for the "Did you mean" suggestion list. */
    private static final int CLOSE_MATCH_DISTANCE = 5;
    /** Cap on the suggestion list to keep the error compact. */
    private static final int SUGGESTION_LIMIT = 5;

    private final RecipeResolver recipeResolver;
    private final RecipeLoader recipeLoader;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final ThinkProcessService thinkProcessService;
    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;
    private final ParentContextSpawnHelper parentContextSpawnHelper;
    private final de.mhus.vance.brain.tools.worktarget.WorkTargetService workTargetService;

    @Override
    public Class<TriggerAction.Recipe> actionType() {
        return TriggerAction.Recipe.class;
    }

    @Override
    public ActionResult execute(ActionInvocation<TriggerAction.Recipe> invocation) {
        TriggerAction.Recipe action = invocation.action();
        TriggerContext rawCtx = invocation.context();

        // Recipe-action spawns require a session — enforced via the sealed
        // TriggerContext hierarchy. A Standalone context is a caller bug;
        // surface it as a structured failure instead of crashing later
        // inside thinkProcessService.create.
        if (!(rawCtx instanceof TriggerContext.Sessioned ctx)) {
            String msg = "SpawnActionExecutor requires a TriggerContext.Sessioned "
                    + "(caller must resolve the session before spawning)";
            log.warn("{} — action='{}' source='{}'",
                    msg, action.recipe(), rawCtx.sourceTag());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR, msg, null);
        }

        // ── Resolve recipe ───────────────────────────────────────────────
        String effectiveProfile = action.connectionProfile() != null
                ? action.connectionProfile()
                : deriveConnectionProfileFromKind(invocation.triggerKind());

        AppliedRecipe applied;
        ThinkEngine engine;
        try {
            applied = recipeResolver.applyDefaulting(
                    ctx.tenantId(), ctx.projectId(),
                    action.recipe(),
                    effectiveProfile, action.params());
            engine = thinkEngineServiceProvider.getObject()
                    .resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
        } catch (RecipeResolver.UnknownRecipeException ure) {
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    ure.getMessage(),
                    buildUnknownRecipeOutput(action.recipe(),
                            ctx.tenantId(), ctx.projectId()));
        } catch (RecipeResolver.UnknownEngineException uee) {
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    uee.getMessage(), null);
        } catch (RuntimeException ex) {
            log.warn("SpawnActionExecutor: resolution failed (recipe='{}'): {}",
                    action.recipe(), ex.toString());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    "resolution: " + ex.getMessage(), null);
        }

        // ── Determine process name + title ───────────────────────────────
        String processName = StringUtils.isNotBlank(action.processName())
                ? action.processName()
                : autoGenerateProcessName();
        String title = StringUtils.isNotBlank(action.title())
                ? action.title()
                : titleFor(invocation);

        // ── Inherit the parent's WorkTarget when the recipe didn't
        // pin one explicitly. Sub-workers spawned from a coding
        // worker land in the same backend by default; a recipe that
        // wants its own (e.g. sandbox-experiment with WORK) keeps
        // precedence.
        Map<String, Object> spawnParams = workTargetService.resolveSpawnParams(
                applied.params(), ctx.parentProcessId());

        // ── Create think-process — handle name-collision as soft-success ─
        ThinkProcessDocument fresh;
        try {
            fresh = thinkProcessService.create(
                    ctx.tenantId(),
                    ctx.projectId(),
                    ctx.parentSessionId(),
                    processName,
                    engine.name(),
                    engine.version(),
                    title,
                    action.goal(),
                    ctx.parentProcessId(),
                    spawnParams,
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
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException ae) {
            return buildAlreadyExistsSoftSuccess(
                    ctx.tenantId(), ctx.parentSessionId(), processName);
        } catch (RuntimeException ex) {
            log.warn("SpawnActionExecutor: process_create failed (name='{}' recipe='{}'): {}",
                    processName, action.recipe(), ex.toString());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    "process_create: " + ex.getMessage(), null);
        }

        // Tag hook-spawned processes so their termination does NOT re-fire
        // process-lifecycle hooks — breaks the self-triggering
        // hook → process → hook chain that would otherwise spawn forever
        // (code-review Phase 2).
        if (invocation.triggerKind() == TriggerKind.HOOK) {
            thinkProcessService.setTriggerSource(fresh.getId(), TriggerKind.HOOK.name());
        }

        // ── Start engine ─────────────────────────────────────────────────
        try {
            thinkEngineServiceProvider.getObject().start(fresh);
        } catch (RuntimeException ex) {
            log.warn("SpawnActionExecutor: engine.start failed for id='{}' recipe='{}': {}",
                    fresh.getId(), action.recipe(), ex.toString());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    "engine_start: " + ex.getMessage(),
                    Map.of("processId", fresh.getId()));
        }

        // ── Inherit-context wrap on initialMessage ───────────────────────
        @Nullable String wrappedInitial = wrapInitialMessage(
                action.initialMessage(),
                action.inheritContextLevel(),
                applied,
                ctx.parentProcessId(),
                fresh.getId());

        // ── Push wrapped initialMessage as USER_CHAT_INPUT ───────────────
        boolean steered = false;
        if (StringUtils.isNotBlank(wrappedInitial)) {
            steered = pushInitialMessage(invocation, fresh.getId(), wrappedInitial);
        }

        log.debug("SpawnActionExecutor: spawned id='{}' name='{}' recipe='{}' engine='{}' "
                        + "session='{}' source='{}' steered={}",
                fresh.getId(), processName,
                action.recipe(), engine.name(),
                ctx.parentSessionId(), ctx.sourceTag(), steered);
        return ActionResult.scheduled(fresh.getId(),
                buildSpawnOutput(fresh, applied,
                        StringUtils.isNotBlank(action.initialMessage()), steered));
    }

    // ──────────────────── Helpers ────────────────────

    /**
     * Wraps {@code initialMessage} with a {@code ## Parent context} block
     * via {@link ParentContextSpawnHelper}. The helper returns the
     * original message unchanged when there is no parent process; when
     * the level resolves to {@code none} a one-line parent-pointer
     * footer is appended.
     *
     * <p>Level priority: explicit {@code action.inheritContextLevel()}
     * → recipe param {@code inheritContext} → helper default ({@code chat}).
     */
    private @Nullable String wrapInitialMessage(
            @Nullable String initialMessage,
            @Nullable String explicitLevel,
            @Nullable AppliedRecipe applied,
            @Nullable String parentProcessId,
            String freshId) {
        if (initialMessage == null || initialMessage.isBlank()) {
            return null;
        }
        if (parentProcessId == null) {
            return initialMessage;
        }
        String level = explicitLevel;
        if (level == null && applied != null && applied.params() != null) {
            Object v = applied.params().get("inheritContext");
            if (v instanceof String s) level = s;
        }
        try {
            return parentContextSpawnHelper.wrap(level, parentProcessId, initialMessage);
        } catch (RuntimeException e) {
            log.warn("SpawnActionExecutor: inheritContext wrap failed for id='{}': {}",
                    freshId, e.toString());
            return initialMessage;
        }
    }

    private boolean pushInitialMessage(
            ActionInvocation<TriggerAction.Recipe> invocation,
            String processId, String content) {
        EngineMessageRouter router = messageRouterProvider.getIfAvailable();
        if (router == null) {
            log.warn("SpawnActionExecutor: EngineMessageRouter unavailable — "
                    + "initialMessage skipped for process '{}'", processId);
            return false;
        }
        TriggerContext ctx = invocation.context();
        String from;
        if (ctx.parentProcessId() != null && !ctx.parentProcessId().isBlank()) {
            from = "process:" + ctx.parentProcessId();
        } else if (StringUtils.isNotBlank(ctx.sourceTag())) {
            from = ctx.sourceTag();
        } else {
            from = invocation.triggerKind().name().toLowerCase(Locale.ROOT);
        }
        PendingMessageDocument msg = PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.now())
                .fromUser(from)
                .content(content)
                .build();
        boolean delivered = router.dispatch(ctx.parentProcessId(), processId, msg);
        if (!delivered) {
            log.warn("SpawnActionExecutor: initialMessage dispatch failed for process '{}' (source='{}')",
                    processId, from);
        }
        return delivered;
    }

    private Map<String, Object> buildSpawnOutput(
            ThinkProcessDocument fresh,
            AppliedRecipe applied,
            boolean initialMessageSet,
            boolean steered) {
        ThinkProcessDocument refreshed = thinkProcessService.findById(fresh.getId())
                .orElse(fresh);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processId", refreshed.getId());
        out.put("name", refreshed.getName());
        out.put("status", refreshed.getStatus() == null ? null : refreshed.getStatus().name());
        out.put("engine", refreshed.getThinkEngine());
        out.put("engineVersion", refreshed.getThinkEngineVersion());
        if (refreshed.getRecipeName() != null) {
            out.put("recipe", refreshed.getRecipeName());
        }
        if (initialMessageSet) {
            out.put("steered", steered);
        }
        return out;
    }

    /**
     * Builds the idempotent already-exists soft-success result. The
     * existing process's id, status, engine, recipe are included so the
     * caller (typically {@code ProcessCreateTool}) can render its hint.
     */
    private ActionResult buildAlreadyExistsSoftSuccess(
            String tenantId, String sessionId, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "already_exists");
        out.put("name", name);
        thinkProcessService.findByName(tenantId, sessionId, name)
                .ifPresent(existing -> {
                    out.put("existingProcessId", existing.getId());
                    if (existing.getStatus() != null) {
                        out.put("existingStatus", existing.getStatus().name());
                    }
                    if (existing.getThinkEngine() != null) {
                        out.put("existingEngine", existing.getThinkEngine());
                    }
                    if (existing.getRecipeName() != null) {
                        out.put("existingRecipe", existing.getRecipeName());
                    }
                });
        out.put("hint", "A process with this name already exists in the "
                + "current session. To send additional input to it, call "
                + "`process_steer(name=\"" + name + "\", content=…)`. To run "
                + "a SECOND process in parallel on a similar topic, retry "
                + "with a different `name`. Do NOT silently retry with the "
                + "same name — the original spawn already succeeded.");
        log.info("SpawnActionExecutor: name='{}' already exists in session='{}' — soft-success",
                name, sessionId);
        return ActionResult.success(out);
    }

    /**
     * Builds the structured output for an unknown-recipe failure:
     * {@code {requested, suggestions:[...], available:[...]}}. Callers
     * (notably {@code ProcessCreateTool}) format this into a Tool-Exception
     * message; non-Tool callers see only the bare {@code errorMessage()}.
     */
    private @Nullable Map<String, Object> buildUnknownRecipeOutput(
            @Nullable String requested, String tenantId, @Nullable String projectId) {
        if (requested == null || requested.isBlank()) return null;
        try {
            List<String> all = recipeLoader.listAll(tenantId, projectId).stream()
                    .map(ResolvedRecipe::name)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .distinct()
                    .toList();
            List<String> suggestions = closeMatches(requested, all);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requested", requested);
            out.put("suggestions", suggestions);
            out.put("available", all);
            return out;
        } catch (RuntimeException e) {
            log.warn("SpawnActionExecutor: failed to build unknown-recipe output for '{}': {}",
                    requested, e.toString());
            return null;
        }
    }

    static List<String> closeMatches(String requested, List<String> candidates) {
        String needle = requested == null ? "" : requested.toLowerCase(Locale.ROOT).trim();
        if (needle.isEmpty() || candidates.isEmpty()) return List.of();
        List<String[]> ranked = new ArrayList<>(candidates.size());
        for (String c : candidates) {
            int d = levenshtein(needle, c.toLowerCase(Locale.ROOT));
            if (d <= CLOSE_MATCH_DISTANCE) {
                ranked.add(new String[]{c, Integer.toString(d)});
            }
        }
        ranked.sort(Comparator.comparingInt(a -> Integer.parseInt(a[1])));
        return ranked.stream()
                .limit(SUGGESTION_LIMIT)
                .map(a -> a[0])
                .toList();
    }

    /** Two-row Levenshtein — fine for short recipe names. */
    private static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    private static String autoGenerateProcessName() {
        return "run_" + Instant.now().toEpochMilli();
    }

    private static String deriveConnectionProfileFromKind(TriggerKind kind) {
        return switch (kind) {
            case SCHEDULER -> "scheduler";
            case EVENT -> "event";
            case WORKFLOW -> "workflow";
            case TOOL -> "tool";
            case USER -> "user";
            case HOOK -> "hook";
        };
    }

    private static String titleFor(ActionInvocation<TriggerAction.Recipe> invocation) {
        String tag = invocation.context().sourceTag();
        if (StringUtils.isNotBlank(tag)) {
            return tag;
        }
        TriggerAction.Recipe a = invocation.action();
        String label = StringUtils.defaultIfBlank(a.recipe(), "default");
        return invocation.triggerKind().name() + ": " + label;
    }
}
