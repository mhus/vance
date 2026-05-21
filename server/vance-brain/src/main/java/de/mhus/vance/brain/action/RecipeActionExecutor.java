package de.mhus.vance.brain.action;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spawn a ThinkProcess from a {@link TriggerAction.Recipe} via the
 * shared {@code applyDefaulting → engine.resolve → create} path.
 *
 * <p>Async by design — returns {@link ActionOutcome#SCHEDULED} carrying
 * the new process id. The session under which the process lives is
 * caller-supplied via {@link TriggerContext#parentSessionId()} (the
 * scheduler resolves a system session, a workflow task uses
 * {@code _magrathea_<runId>}, an LLM tool reuses its caller-process session).
 *
 * <p>An {@code initialMessage} on the action is dispatched as a
 * {@code USER_CHAT_INPUT} pending message right after spawn — same
 * semantics as in {@code ProcessCreateTool}.
 *
 * <p>Event-log writes and trigger-specific metrics are not done here —
 * those stay on the trigger side (scheduler / event / tool) so each
 * surface keeps its own source tags and counters.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class RecipeActionExecutor implements ActionExecutor<TriggerAction.Recipe> {

    private final RecipeResolver recipeResolver;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final ThinkProcessService thinkProcessService;
    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;

    @Override
    public Class<TriggerAction.Recipe> actionType() {
        return TriggerAction.Recipe.class;
    }

    @Override
    public ActionResult execute(ActionInvocation<TriggerAction.Recipe> invocation) {
        TriggerAction.Recipe action = invocation.action();
        TriggerContext ctx = invocation.context();

        if (StringUtils.isBlank(ctx.parentSessionId())) {
            String msg = "RecipeActionExecutor requires a parentSessionId on TriggerContext "
                    + "(caller must resolve the session before spawning a recipe)";
            log.warn("{} — action='{}' source='{}'", msg, action.recipe(), ctx.sourceTag());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR, msg, null);
        }

        Optional<AppliedRecipe> appliedOpt;
        try {
            appliedOpt = recipeResolver.applyDefaulting(
                    ctx.tenantId(),
                    ctx.projectId(),
                    action.recipe(),
                    /*engineName*/ null,
                    /*connectionProfile*/ deriveConnectionProfile(invocation.triggerKind()),
                    action.params());
        } catch (RuntimeException ex) {
            log.warn("RecipeActionExecutor: recipe '{}' resolution failed: {}",
                    action.recipe(), ex.toString());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    "recipe_resolution: " + ex.getMessage(), null);
        }
        if (appliedOpt.isEmpty()) {
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    "unknown recipe '" + action.recipe() + "'", null);
        }
        AppliedRecipe applied = appliedOpt.get();

        ThinkEngine engine;
        try {
            engine = thinkEngineServiceProvider.getObject()
                    .resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
        } catch (RuntimeException ex) {
            log.warn("RecipeActionExecutor: engine '{}' resolution failed: {}",
                    applied.engine(), ex.toString());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    "engine_resolution: " + ex.getMessage(), null);
        }

        String processName = "run_" + Instant.now().toEpochMilli();
        ThinkProcessDocument fresh;
        try {
            fresh = thinkProcessService.create(
                    ctx.tenantId(),
                    ctx.projectId(),
                    ctx.parentSessionId(),
                    processName,
                    engine.name(),
                    engine.version(),
                    /*title*/ titleFor(invocation),
                    /*goal*/ null,
                    ctx.parentProcessId(),
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
                            ? null : java.util.Set.copyOf(applied.allowedSkills()));
        } catch (RuntimeException ex) {
            log.warn("RecipeActionExecutor: think-process create failed for recipe '{}': {}",
                    action.recipe(), ex.toString());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    "process_create: " + ex.getMessage(), null);
        }

        try {
            thinkEngineServiceProvider.getObject().start(fresh);
        } catch (RuntimeException ex) {
            log.warn("RecipeActionExecutor: engine.start failed for process '{}' (recipe '{}'): {}",
                    fresh.getId(), action.recipe(), ex.toString());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    "engine_start: " + ex.getMessage(),
                    Map.of("processId", fresh.getId()));
        }

        if (StringUtils.isNotBlank(action.initialMessage())) {
            deliverInitialMessage(invocation, fresh.getId(), action.initialMessage());
        }

        log.debug("RecipeActionExecutor: spawned process id='{}' name='{}' recipe='{}' "
                        + "engine='{}' session='{}' source='{}'",
                fresh.getId(), processName, action.recipe(), engine.name(),
                ctx.parentSessionId(), ctx.sourceTag());
        return ActionResult.scheduled(fresh.getId());
    }

    private void deliverInitialMessage(ActionInvocation<TriggerAction.Recipe> invocation,
                                       String processId, String content) {
        EngineMessageRouter router = messageRouterProvider.getIfAvailable();
        if (router == null) {
            log.warn("RecipeActionExecutor: EngineMessageRouter unavailable — "
                    + "initialMessage skipped for process '{}'", processId);
            return;
        }
        TriggerContext ctx = invocation.context();
        String from = StringUtils.defaultIfBlank(ctx.sourceTag(),
                invocation.triggerKind().name().toLowerCase());
        PendingMessageDocument msg = PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.now())
                .fromUser(from)
                .content(content)
                .build();
        boolean delivered = router.dispatch(/*sourceProcessId*/ null, processId, msg);
        if (!delivered) {
            log.warn("RecipeActionExecutor: initialMessage dispatch failed for process '{}' (source='{}')",
                    processId, from);
        }
    }

    private static String deriveConnectionProfile(TriggerKind kind) {
        return switch (kind) {
            case SCHEDULER -> "scheduler";
            case EVENT -> "event";
            case WORKFLOW_TASK -> "workflow";
            case TOOL -> "tool";
            case MANUAL -> "manual";
        };
    }

    private static String titleFor(ActionInvocation<TriggerAction.Recipe> invocation) {
        String tag = invocation.context().sourceTag();
        if (StringUtils.isNotBlank(tag)) {
            return tag;
        }
        return invocation.triggerKind().name() + ": " + invocation.action().recipe();
    }
}
