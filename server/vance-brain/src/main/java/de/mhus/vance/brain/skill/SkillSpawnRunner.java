package de.mhus.vance.brain.skill;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Spawns the worker for a {@code run.target: spawn} skill and hands it to
 * the regular activation on its own lane.
 *
 * <p>Deliberately does <b>not</b> extend the spawn path: the child is
 * created through the unchanged {@code TriggerAction.Recipe} route
 * <em>without</em> an {@code initialMessage}, which starts the engine but
 * fires no turn. The skill then lands on the child through
 * {@link SkillSteerProcessor#activate} like any other explicit
 * activation — sticky registration, {@code activate:} commands,
 * {@code tools:} / {@code manualPaths:}, and the rendered {@code action:}
 * as its first user message. Everything the child needs already exists in
 * the skill layer, so {@code vance-api} and {@code SpawnActionExecutor}
 * stay untouched. See {@code planning/skill-spawn-target.md} §2.3.
 *
 * <p><b>Naming.</b> Process names are unique per session, and a collision
 * is not an error at the executor level — it returns an idempotent
 * already-exists soft-success whose output carries no {@code processId}.
 * Taking that at face value would silently skip the activation while
 * looking like success, so this runner allocates {@code <skill>-<n>} and
 * treats the soft-success as "name taken, try the next index".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillSpawnRunner {

    /**
     * How many indices to try before giving up. Only a race between two
     * processes spawning the same skill in the same session can burn more
     * than one — the first index comes from a fresh scan.
     */
    static final int MAX_NAME_ATTEMPTS = 5;

    /** Marker the spawn executor puts in its already-exists soft-success. */
    private static final String STATUS_ALREADY_EXISTS = "already_exists";

    private final ActionExecutorRegistry actionRegistry;
    private final LaneScheduler laneScheduler;
    private final ThinkProcessService thinkProcessService;

    /**
     * Creates the worker and schedules {@code childActivation} on its
     * lane. Returns the child's process id.
     *
     * @param childActivation what to run once the child exists — in
     *     practice {@code SkillSteerProcessor.activate} for the same
     *     skill, which is what turns the bare worker into a skilled one.
     *     Runs on the child's lane, never on the caller's.
     */
    public String spawn(
            ThinkProcessDocument parent,
            ResolvedSkill skill,
            Consumer<ThinkProcessDocument> childActivation) {
        SkillRun run = skill.run();
        if (!run.spawns() || run.recipe() == null) {
            throw new SkillSpawnException(skill.name(), "skill does not declare run.target: spawn");
        }
        int index = nextFreeIndex(parent, skill.name());
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++, index++) {
            String childName = skill.name() + "-" + index;
            ActionResult result = execute(parent, skill, childName);
            if (result.outcome().isFailure()) {
                throw new SkillSpawnException(skill.name(),
                        "recipe '" + run.recipe() + "' → " + result.outcome()
                                + ": " + result.errorMessage());
            }
            if (isAlreadyExists(result)) {
                log.debug("Skill spawn name='{}' already taken — trying next index", childName);
                continue;
            }
            String childId = result.spawnedId();
            if (childId == null || childId.isBlank()) {
                throw new SkillSpawnException(skill.name(),
                        "spawn returned " + result.outcome() + " without a process id");
            }
            log.info("Skill spawn id='{}' name='{}' → child='{}' id='{}' recipe='{}' inherit='{}'",
                    parent.getId(), skill.name(), childName, childId,
                    run.recipe(), run.inherit());
            scheduleActivation(childId, childName, skill, childActivation);
            return childId;
        }
        throw new SkillSpawnException(skill.name(),
                "no free process name after " + MAX_NAME_ATTEMPTS + " attempts (base '"
                        + skill.name() + "')");
    }

    private ActionResult execute(
            ThinkProcessDocument parent, ResolvedSkill skill, String childName) {
        SkillRun run = skill.run();
        // No initialMessage: the executor would wrap it with the parent's
        // chat history and push it before the skill is on the child. The
        // task rides in with the activation instead (see class javadoc).
        // The goal stays a one-liner — what this worker is for — because
        // the actual task is rendered against the *child* in fireAction,
        // and rendering it twice against two different contexts would let
        // the two texts drift apart.
        TriggerAction.Recipe action = new TriggerAction.Recipe(
                run.recipe(),
                childName,
                skill.title(),
                skill.description(),
                run.inherit(),
                parent.getConnectionProfile(),
                /*initialMessage*/ null,
                /*params*/ null,
                /*runAs*/ null);
        TriggerContext ctx = TriggerContext.sessioned(
                parent.getTenantId(),
                parent.getProjectId(),
                /*resolvedRunAs*/ null,
                /*correlationId*/ null,
                /*sourceTag*/ "skill:" + skill.name(),
                parent.getSessionId(),
                parent.getId());
        try {
            return actionRegistry.execute(action, ctx, TriggerKind.TOOL);
        } catch (RuntimeException e) {
            throw new SkillSpawnException(skill.name(),
                    "recipe '" + run.recipe() + "' → " + e, e);
        }
    }

    /**
     * Runs the activation on the child's lane — the established pattern
     * for touching a freshly spawned process (cf. {@code ZaphodEngine},
     * {@code AgentTaskExecutor}). The document is re-read inside the lane
     * task so the activation writes against current state.
     */
    private void scheduleActivation(
            String childId,
            String childName,
            ResolvedSkill skill,
            Consumer<ThinkProcessDocument> childActivation) {
        laneScheduler.submit(childId, () -> {
            ThinkProcessDocument child = thinkProcessService.findById(childId).orElse(null);
            if (child == null) {
                log.warn("Skill spawn: child '{}' (id='{}') disappeared before activation "
                        + "of skill '{}'", childName, childId, skill.name());
                return;
            }
            try {
                childActivation.accept(child);
            } catch (RuntimeException e) {
                // The worker exists but has no skill and no task — it would
                // sit idle forever. Loud log, and close it so it does not
                // linger in the session listing as a mystery.
                log.warn("Skill spawn: activating '{}' on child '{}' failed: {}",
                        skill.name(), childName, e.toString(), e);
                closeOrphan(childId);
            }
        });
    }

    private void closeOrphan(String childId) {
        try {
            thinkProcessService.closeProcess(
                    childId, de.mhus.vance.api.thinkprocess.CloseReason.ABANDONED);
        } catch (RuntimeException e) {
            log.warn("Skill spawn: could not close orphaned child id='{}': {}",
                    childId, e.toString());
        }
    }

    /**
     * Smallest index not yet used by a {@code <skill>-<n>} process in this
     * session. Closed processes count — their names are still taken, and
     * keeping the indices monotonic leaves a readable history
     * ({@code code-review-1}, {@code code-review-2}, …).
     */
    private int nextFreeIndex(ThinkProcessDocument parent, String skillName) {
        String prefix = skillName + "-";
        int max = 0;
        List<ThinkProcessDocument> siblings =
                thinkProcessService.findBySession(parent.getTenantId(), parent.getSessionId());
        for (ThinkProcessDocument p : siblings) {
            String name = p.getName();
            if (name == null || !name.startsWith(prefix)) continue;
            try {
                max = Math.max(max, Integer.parseInt(name.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // `code-review-of-tuesday` is somebody else's process name,
                // not one of ours — it blocks nothing.
            }
        }
        return max + 1;
    }

    private static boolean isAlreadyExists(ActionResult result) {
        Map<String, Object> out = result.output();
        return out != null && STATUS_ALREADY_EXISTS.equals(out.get("status"));
    }
}
