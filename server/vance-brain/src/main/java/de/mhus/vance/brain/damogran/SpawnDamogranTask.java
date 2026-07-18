package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.requireString;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.string;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code spawn} task: spawns a worker process via the shared
 * {@link ActionExecutorRegistry} using a {@code recipe}. The spawned worker
 * inherits the compose process's WorkTarget, so it operates on the same
 * workspace.
 *
 * <p>Fire-and-forget in v1: the spawn returns immediately with the spawned
 * process id (no blocking, no output capture). For a synchronous "analyse the
 * workspace and produce a file" step, use the {@code llm} task instead. Spawns
 * require an owning session (the compose process's session).
 */
@Service
class SpawnDamogranTask implements DamogranTask {

    private final ActionExecutorRegistry actionRegistry;
    private final ThinkProcessService thinkProcessService;

    SpawnDamogranTask(ActionExecutorRegistry actionRegistry, ThinkProcessService thinkProcessService) {
        this.actionRegistry = actionRegistry;
        this.thinkProcessService = thinkProcessService;
    }

    @Override
    public String type() {
        return "spawn";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        if (ctx.processId() == null) {
            return DamogranTaskResult.failure("spawn task requires a process context");
        }
        Optional<ThinkProcessDocument> owner = thinkProcessService.findById(ctx.processId());
        if (owner.isEmpty()) {
            return DamogranTaskResult.failure("spawn: owning process not found");
        }
        String sessionId = owner.get().getSessionId();
        if (StringUtils.isBlank(sessionId)) {
            return DamogranTaskResult.failure("spawn: owning process has no session");
        }

        String recipe = requireString(spec, "recipe");
        String initialMessage = string(spec, "prompt");

        TriggerAction.Recipe action =
                TriggerAction.Recipe.of(recipe, initialMessage, spec.params(), null);
        TriggerContext triggerContext = TriggerContext.sessioned(
                ctx.tenantId(), ctx.projectId(), null, null, "damogran:spawn",
                sessionId, ctx.processId());

        ActionResult result = actionRegistry.execute(action, triggerContext, TriggerKind.TOOL);

        if (result.outcome().isFailure()) {
            String error = result.errorMessage() != null
                    ? result.errorMessage()
                    : "spawn failed: " + result.outcome();
            return DamogranTaskResult.failure(error);
        }
        return DamogranTaskResult.success(List.of(), "spawned process " + result.spawnedId());
    }
}
