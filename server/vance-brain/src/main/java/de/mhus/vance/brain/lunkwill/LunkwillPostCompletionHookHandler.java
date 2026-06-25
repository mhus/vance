package de.mhus.vance.brain.lunkwill;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.recipe.PostCompletionHookConfig;
import de.mhus.vance.brain.recipe.PostCompletionHookTrigger;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides whether to spawn a post-completion hook process for a
 * Lunkwill worker that just hit a stop signal, and dispatches the
 * spawn through the central {@link ActionExecutorRegistry}.
 *
 * <p>Lives outside {@link LunkwillEngine} so the spawn decision has a
 * narrow test surface (no LLM streaming, no compaction, no prompt
 * assembly). The engine calls {@link #maybeSpawn} after persisting the
 * worker's final reply and before transitioning to {@code IDLE}.
 *
 * <p>See {@code planning/lunkwill-post-completion-hook.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LunkwillPostCompletionHookHandler {

    /** Suffix added to the worker name to form the hook process name. */
    private static final String HOOK_NAME_SUFFIX = "_hook_";

    private final ThinkProcessService thinkProcessService;
    private final RecipeResolver recipeResolver;
    private final ActionExecutorRegistry actionRegistry;
    private final PromptTemplateRenderer templateRenderer;
    private final ChatMessageService chatMessageService;

    /**
     * Checks every gate (recipe configured? trigger matches? round-cap
     * not exceeded? inbox isn't already carrying a hook outcome?) and
     * spawns the hook process when all pass. Returns {@code true} iff
     * a spawn was issued.
     *
     * <p>Failures (unknown hook recipe, malformed template, executor
     * error) are logged at WARN and treated as "no spawn" — they must
     * never propagate up and break the worker's natural stop.
     *
     * @param worker        the worker process that just hit the stop signal
     * @param finalText     the worker's final reply (last LLM text)
     * @param drainedInbox  inbox messages drained at the start of this turn
     * @param naturalStop   true when the stop signal is a natural stop,
     *                      false when it is a tool-driven terminate
     */
    public boolean maybeSpawn(
            ThinkProcessDocument worker,
            String finalText,
            List<SteerMessage> drainedInbox,
            boolean naturalStop) {
        PostCompletionHookConfig hookConfig = resolveHookConfig(worker);
        if (hookConfig == null) {
            return false;
        }
        if (hookConfig.maxRounds() <= 0) {
            return false;
        }
        PostCompletionHookTrigger trigger = hookConfig.trigger();
        if (naturalStop && !trigger.firesOnNaturalStop()) {
            return false;
        }
        if (!naturalStop && !trigger.firesOnTerminate()) {
            return false;
        }
        if (worker.getPostCompletionHookRounds() >= hookConfig.maxRounds()) {
            log.debug(
                    "Lunkwill post-hook id='{}' round-cap reached ({} >= {}), skipping spawn",
                    worker.getId(),
                    worker.getPostCompletionHookRounds(),
                    hookConfig.maxRounds());
            return false;
        }
        if (hasHookOutcomeInInbox(drainedInbox)) {
            log.debug(
                    "Lunkwill post-hook id='{}' inbox already carries a hook outcome, "
                            + "suppressing recursive spawn",
                    worker.getId());
            return false;
        }

        // Validate the hook recipe exists and is engine-compatible. We
        // do this lazily (here, at spawn-time) rather than at recipe-
        // load-time because the cascade is tenant-scoped — the loader
        // has no tenant context for the cross-recipe check.
        Optional<ResolvedRecipe> hookRecipeOpt = recipeResolver.resolve(
                worker.getTenantId(), worker.getProjectId(), hookConfig.recipe());
        if (hookRecipeOpt.isEmpty()) {
            log.warn(
                    "Lunkwill post-hook id='{}' recipe='{}' unknown — skipping spawn",
                    worker.getId(), hookConfig.recipe());
            return false;
        }
        ResolvedRecipe hookRecipe = hookRecipeOpt.get();
        if (!LunkwillEngine.NAME.equalsIgnoreCase(hookRecipe.engine())) {
            log.warn(
                    "Lunkwill post-hook id='{}' recipe='{}' uses engine='{}' "
                            + "(must be '{}') — skipping spawn",
                    worker.getId(), hookConfig.recipe(),
                    hookRecipe.engine(), LunkwillEngine.NAME);
            return false;
        }
        if (hookRecipe.postCompletionHook() != null) {
            log.warn(
                    "Lunkwill post-hook id='{}' recipe='{}' itself declares "
                            + "postCompletionHook — refusing transitive loop",
                    worker.getId(), hookConfig.recipe());
            return false;
        }

        int roundIndex = worker.getPostCompletionHookRounds();
        String renderedGoal = renderGoal(hookConfig, worker, finalText, roundIndex);
        String hookProcessName = buildHookProcessName(worker.getName(), roundIndex);

        // Atomically bump the round counter BEFORE the spawn so a
        // concurrent retry can't double-spawn (the spawn is an
        // asynchronous executor handoff — Mongo is the synchronisation
        // point).
        int newRoundCount = thinkProcessService.incrementPostCompletionHookRounds(
                worker.getId());
        if (newRoundCount < 0) {
            log.warn(
                    "Lunkwill post-hook id='{}' counter increment failed (row gone?), skipping spawn",
                    worker.getId());
            return false;
        }

        TriggerAction.Recipe action = new TriggerAction.Recipe(
                hookConfig.recipe(),
                hookProcessName,
                /*title*/ null,
                renderedGoal,
                /*inheritContextLevel*/ null,
                worker.getConnectionProfile(),
                /*initialMessage*/ renderedGoal,
                /*params*/ null,
                /*runAs*/ null);
        TriggerContext triggerCtx = TriggerContext.sessioned(
                worker.getTenantId(),
                worker.getProjectId(),
                /*resolvedRunAs*/ null,
                /*correlationId*/ null,
                /*sourceTag*/ "engine:lunkwill:post-completion-hook",
                worker.getSessionId(),
                worker.getId());

        try {
            ActionResult result = actionRegistry.execute(
                    action, triggerCtx, TriggerKind.TOOL);
            if (result.outcome().isFailure()) {
                log.warn(
                        "Lunkwill post-hook id='{}' recipe='{}' executor returned {}: {}",
                        worker.getId(), hookConfig.recipe(),
                        result.outcome(), result.output());
                return false;
            }
            log.info(
                    "Lunkwill post-hook id='{}' spawned recipe='{}' as '{}' (round {} of {})",
                    worker.getId(), hookConfig.recipe(), hookProcessName,
                    newRoundCount, hookConfig.maxRounds());
            return true;
        } catch (RuntimeException e) {
            log.warn(
                    "Lunkwill post-hook id='{}' recipe='{}' spawn threw: {}",
                    worker.getId(), hookConfig.recipe(), e.toString());
            return false;
        }
    }

    /**
     * Looks up the hook config for the worker's recipe. Returns
     * {@code null} when no recipe name is set on the process or the
     * recipe carries no hook block.
     */
    private @Nullable PostCompletionHookConfig resolveHookConfig(
            ThinkProcessDocument worker) {
        String recipeName = worker.getRecipeName();
        if (recipeName == null || recipeName.isBlank()) {
            return null;
        }
        try {
            Optional<ResolvedRecipe> recipeOpt = recipeResolver.resolve(
                    worker.getTenantId(), worker.getProjectId(), recipeName);
            return recipeOpt.map(ResolvedRecipe::postCompletionHook).orElse(null);
        } catch (RuntimeException e) {
            log.warn(
                    "Lunkwill post-hook id='{}' recipe='{}' resolve failed: {}",
                    worker.getId(), recipeName, e.toString());
            return null;
        }
    }

    /**
     * Reentry guard: any {@code ProcessEvent} in the drained inbox is
     * a strong signal that this turn is the worker's reaction to a
     * previous spawn (hook or otherwise). Spawning another hook on top
     * of that would loop. The Round-Cap is the harder backstop; this
     * is the cheap synchronous check.
     */
    private static boolean hasHookOutcomeInInbox(List<SteerMessage> drainedInbox) {
        if (drainedInbox == null || drainedInbox.isEmpty()) return false;
        for (SteerMessage m : drainedInbox) {
            if (m instanceof SteerMessage.ProcessEvent) {
                return true;
            }
        }
        return false;
    }

    private String renderGoal(
            PostCompletionHookConfig config,
            ThinkProcessDocument worker,
            String finalText,
            int roundIndex) {
        String template = config.goalTemplate() != null
                ? config.goalTemplate()
                : defaultGoalTemplate();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("finalText", finalText == null ? "" : finalText);
        vars.put("originalGoal", firstUserInput(worker));
        vars.put("chatHistoryText", chatHistoryAsPlainText(worker));
        vars.put("engine", LunkwillEngine.NAME);
        vars.put("recipe", config.recipe());
        vars.put("roundIndex", roundIndex);
        vars.put("tenantId", worker.getTenantId());
        vars.put("projectId", worker.getProjectId());
        vars.put("sessionId", worker.getSessionId());
        String rendered = templateRenderer.render(template, vars);
        return rendered == null ? "" : rendered;
    }

    private String firstUserInput(ThinkProcessDocument worker) {
        try {
            List<ChatMessageDocument> history = chatMessageService.activeHistory(
                    worker.getTenantId(),
                    worker.getSessionId(),
                    worker.getId());
            for (ChatMessageDocument m : history) {
                if (m.getRole() == ChatRole.USER
                        && m.getContent() != null
                        && !m.getContent().isBlank()) {
                    return m.getContent();
                }
            }
        } catch (RuntimeException e) {
            log.debug("firstUserInput lookup failed for id='{}': {}",
                    worker.getId(), e.toString());
        }
        String goal = worker.getGoal();
        return goal == null ? "" : goal;
    }

    private String chatHistoryAsPlainText(ThinkProcessDocument worker) {
        try {
            List<ChatMessageDocument> history = chatMessageService.activeHistory(
                    worker.getTenantId(),
                    worker.getSessionId(),
                    worker.getId());
            StringBuilder sb = new StringBuilder();
            for (ChatMessageDocument m : history) {
                if (m.getContent() == null || m.getContent().isBlank()) continue;
                sb.append(m.getRole().name()).append(": ")
                        .append(m.getContent()).append("\n\n");
            }
            return sb.toString().trim();
        } catch (RuntimeException e) {
            log.debug("chatHistoryAsPlainText lookup failed for id='{}': {}",
                    worker.getId(), e.toString());
            return "";
        }
    }

    private static String defaultGoalTemplate() {
        return """
                The sibling worker just signaled a stop on its task.

                Original task:
                {{ originalGoal }}

                Worker's final answer:
                {{ finalText }}

                Perform your configured follow-up action and terminate with a
                clear outcome. Use your recipe's task-complete tool to signal done.
                """;
    }

    private static String buildHookProcessName(@Nullable String workerName, int roundIndex) {
        String base = (workerName == null || workerName.isBlank()) ? "worker" : workerName;
        return base + HOOK_NAME_SUFFIX + roundIndex;
    }
}
