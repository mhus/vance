package de.mhus.vance.brain.benjy;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spawns Benjy's doing workers — one focused Ford turn per item, fresh
 * context on every attempt (planning/benjy-engine.md #5: retry = fresh
 * spawn with the error context in the prompt, never a re-steer on the
 * same worker whose history would grow).
 *
 * <p>Mirrors the {@code AgentTaskExecutor} spawn mechanics: resolve the
 * recipe, create the process parented to Benjy, then start + seed the
 * prompt in one lane task so no turn can slip in between. The worker's
 * reply arrives asynchronously as a {@code SteerMessage.Reply} in
 * Benjy's pending queue (the emitReply parent-routing path), which
 * wakes Benjy's lane.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BenjyWorkerSpawner {

    private final RecipeResolver recipeResolver;
    private final ThinkProcessService thinkProcessService;
    /**
     * {@link ObjectProvider} breaks the bean cycle: BenjyEngine → this
     * spawner → ThinkEngineService → List&lt;ThinkEngine&gt; → BenjyEngine.
     * Same lazy-wiring reason as {@code SpawnActionExecutor}.
     */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    private final LaneScheduler laneScheduler;
    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;
    private final WorkTargetService workTargetService;

    /**
     * Spawns a worker for one item and returns its process id.
     *
     * @param parent       the Benjy process — becomes the worker's parent so
     *                     replies route into its pending queue
     * @param recipeName   the doing recipe ({@code benjy-do-coding} or the
     *                     escalation fallback recipe)
     * @param extraParams  per-spawn engine params ({@code maxIterations} for the
     *                     tool budget, {@code thinking} for the reasoning level);
     *                     merged over the recipe defaults, same as the tool spawn path
     * @param prompt       the focused item prompt — the worker's goal and first message
     */
    public String spawn(
            ThinkProcessDocument parent, String recipeName, Map<String, Object> extraParams, String prompt) {
        AppliedRecipe applied = recipeResolver.applyDefaulting(
                parent.getTenantId(), parent.getProjectId(), recipeName, /* connectionProfile */ null, extraParams);
        ThinkEngine engine = thinkEngineServiceProvider
                .getObject()
                .resolve(applied.engine())
                .orElseThrow(() -> new IllegalStateException(
                        "Benjy recipe '" + recipeName + "' references unknown engine '" + applied.engine() + "'"));

        String processName = "benjy-do-" + System.nanoTime();
        Map<String, Object> spawnParams = workTargetService.resolveSpawnParams(applied.params(), parent.getId());

        ThinkProcessDocument worker = thinkProcessService.create(
                parent.getTenantId(),
                parent.getProjectId(),
                parent.getSessionId(),
                processName,
                engine.name(),
                engine.version(),
                /*title*/ "Benjy doer",
                /*goal*/ prompt,
                /*parentProcessId*/ parent.getId(),
                spawnParams,
                applied.name(),
                applied.promptOverride(),
                applied.promptMode(),
                applied.effectiveAllowedTools());

        // Start + seed on the worker's lane in ONE task — the lane-serialisation
        // invariant and the "prompt is in the pending queue before the first
        // turn reads it" guarantee (same reasoning as AgentTaskExecutor).
        laneScheduler.submit(worker.getId(), () -> {
            thinkEngineServiceProvider.getObject().start(worker);
            seedPrompt(parent.getId(), worker.getId(), prompt);
            return null;
        });
        log.info("Benjy id='{}' spawned doer id='{}' recipe='{}'", parent.getId(), worker.getId(), recipeName);
        return worker.getId();
    }

    private void seedPrompt(String parentId, String workerId, String prompt) {
        EngineMessageRouter router = messageRouterProvider.getIfAvailable();
        if (router == null) {
            log.warn("Benjy: EngineMessageRouter unavailable — doer prompt not delivered to '{}'", workerId);
            return;
        }
        boolean delivered = router.dispatch(
                /* senderProcessId */ parentId,
                workerId,
                PendingMessageDocument.builder()
                        .type(PendingMessageType.USER_CHAT_INPUT)
                        .at(Instant.now())
                        .fromUser("process:" + parentId)
                        .content(prompt)
                        .build());
        if (!delivered) {
            log.warn("Benjy: doer prompt dispatch failed for process '{}'", workerId);
        }
    }
}
