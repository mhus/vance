package de.mhus.vance.brain.benjy;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * @param inheritedSkills the Benjy hub's sticky active skills
     *                     (planning/skill-activation-levels.md §3.6.5), added
     *                     on top of the doer recipe's {@code defaultActiveSkills}
     *                     and filtered through the doer recipe's
     *                     {@code allowedSkills} whitelist. They arrive as
     *                     regular user-level activations ({@code fromRecipe=false},
     *                     args kept) — the doer recipe did not ask for them, so a
     *                     locked recipe must not pin them. Empty when the hub
     *                     carries none.
     */
    public DoerSpawn spawn(
            ThinkProcessDocument parent,
            String recipeName,
            Map<String, Object> extraParams,
            String prompt,
            List<ActiveSkillRefEmbedded> inheritedSkills) {
        AppliedRecipe applied = recipeResolver.applyDefaulting(
                parent.getTenantId(), parent.getProjectId(), recipeName, /* connectionProfile */ null, extraParams);
        ThinkEngine engine = thinkEngineServiceProvider
                .getObject()
                .resolve(applied.engine())
                .orElseThrow(() -> new IllegalStateException(
                        "Benjy recipe '" + recipeName + "' references unknown engine '" + applied.engine() + "'"));

        String processName = "benjy-do-" + System.nanoTime();
        Map<String, Object> spawnParams = workTargetService.resolveSpawnParams(applied.params(), parent.getId());

        // Hub skill inheritance (§3.6.5): the hub's active skills ride along
        // on top of the doer recipe's defaults, gated by the doer recipe's
        // own allowedSkills whitelist. A skill the whitelist drops is
        // reported back — the engine journals it, never a silent drop.
        Set<String> doerWhitelist = applied.allowedSkills() == null ? null : Set.copyOf(applied.allowedSkills());
        List<String> recipeSkills = applied.defaultActiveSkills();
        List<ActiveSkillRefEmbedded> extraRefs = new ArrayList<>();
        List<String> droppedSkills = new ArrayList<>();
        Set<String> seen = new HashSet<>(recipeSkills);
        for (ActiveSkillRefEmbedded ref : inheritedSkills) {
            String name = ref.getName();
            if (name == null || name.isBlank() || !seen.add(name)) continue;
            if (doerWhitelist != null && !doerWhitelist.contains(name)) {
                droppedSkills.add(name);
                continue;
            }
            extraRefs.add(ActiveSkillRefEmbedded.builder()
                    .name(name)
                    .resolvedFromScope(ref.getResolvedFromScope())
                    .oneShot(false)
                    .fromRecipe(false)
                    .activatedAt(Instant.now())
                    .args(ref.getArgs())
                    .build());
        }

        // Full create overload — the mid overload silently dropped the recipe's
        // skill config (defaultActiveSkills/allowedSkills) and the audit/append
        // fields. A doer recipe that pins skills has to see them
        // (planning/skill-activation-levels.md §1.3.3).
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
                applied.promptOverrideAppend(),
                applied.promptMode(),
                applied.dataRelayCorrection(),
                applied.effectiveAllowedTools(),
                applied.connectionProfile(),
                recipeSkills,
                doerWhitelist);
        if (!extraRefs.isEmpty()) {
            // Before the first turn (the lane task below starts the worker):
            // the persisted list and the in-memory document the start call
            // reads must both carry the inherited refs.
            List<ActiveSkillRefEmbedded> active =
                    new ArrayList<>(worker.getActiveSkills() == null ? List.of() : worker.getActiveSkills());
            active.addAll(extraRefs);
            thinkProcessService.replaceActiveSkills(worker.getId(), active);
            worker.setActiveSkills(active);
        }

        // Start + seed on the worker's lane in ONE task — the lane-serialisation
        // invariant and the "prompt is in the pending queue before the first
        // turn reads it" guarantee (same reasoning as AgentTaskExecutor).
        laneScheduler.submit(worker.getId(), () -> {
            thinkEngineServiceProvider.getObject().start(worker);
            seedPrompt(parent.getId(), worker.getId(), prompt);
            return null;
        });
        log.info(
                "Benjy id='{}' spawned doer id='{}' recipe='{}' recipeSkills={} inheritedSkills={}",
                parent.getId(),
                worker.getId(),
                recipeName,
                recipeSkills,
                extraRefs.stream().map(ActiveSkillRefEmbedded::getName).toList());
        return new DoerSpawn(worker.getId(), List.copyOf(droppedSkills));
    }

    /**
     * One doer spawn: the worker's process id plus the hub skills the doer
     * recipe's whitelist refused — the engine turns those into a visible
     * journal record instead of losing them silently.
     */
    public record DoerSpawn(String processId, List<String> droppedSkills) {}

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
