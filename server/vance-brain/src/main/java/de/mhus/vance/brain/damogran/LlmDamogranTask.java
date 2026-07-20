package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.requireString;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code llm} task: a single-shot LLM call via {@link LightLlmService}
 * (no process spawn, no lane lock) whose reply is written to a workspace file.
 *
 * <p>The output file is the task's first declared output ({@code output:} /
 * {@code outputs:}); the manifest task params are passed as Pebble variables to
 * the recipe template. The recipe must be {@code internal: true} (enforced by
 * {@link LightLlmService}). WORK target only (the reply lands as a workspace
 * file).
 */
@Service
class LlmDamogranTask implements DamogranTask {

    private final LightLlmService lightLlm;
    private final WorkspaceService workspaceService;

    LlmDamogranTask(LightLlmService lightLlm, WorkspaceService workspaceService) {
        this.lightLlm = lightLlm;
        this.workspaceService = workspaceService;
    }

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        if (spec.declaredOutputs().isEmpty()) {
            return DamogranTaskResult.failure(
                    "llm task requires an output file (declare 'output:' or 'outputs:')");
        }
        String recipe = requireString(spec, "recipe");
        String prompt = requireString(spec, "prompt");
        OutputArtifact target = DamogranTaskSupport.resolveOutputs(spec).get(0);

        String reply = lightLlm.call(LightLlmRequest.builder()
                .recipeName(recipe)
                .userPrompt(prompt)
                .pebbleVars(spec.params())
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .build());

        workspaceService.write(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                target.path(), reply);

        return DamogranTaskResult.success(List.of(target));
    }
}
