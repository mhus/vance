package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.intOr;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.resolveOutputs;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.string;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code js} task: runs a workspace JavaScript file via the shared
 * {@link ActionExecutorRegistry} ({@link ScriptSource#WORKSPACE}) — either an
 * existing {@code script} file or inline {@code code} written to
 * {@code .damogran/inline.js}. The script's return value is captured in the
 * result log.
 *
 * <p>Note: server-side JS runs with no writable filesystem and (today) no live
 * tool surface (the {@code ScriptActionExecutor} tool wiring is pending). So a
 * js task computes and returns a value; file production / LLM-tool calls from
 * within JS are not available yet — use {@code llm}/{@code python}/{@code exec}
 * for those. WORK target only (WORKSPACE scripts need a server RootDir).
 */
@Service
class JsDamogranTask implements DamogranTask {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final String INLINE_PATH = ".damogran/inline.js";

    private final ActionExecutorRegistry actionRegistry;
    private final WorkspaceService workspaceService;

    JsDamogranTask(ActionExecutorRegistry actionRegistry, WorkspaceService workspaceService) {
        this.actionRegistry = actionRegistry;
        this.workspaceService = workspaceService;
    }

    @Override
    public String type() {
        return "js";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        String scriptPath;
        String code = string(spec, "code");
        if (code != null) {
            workspaceService.write(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                    INLINE_PATH, code);
            scriptPath = INLINE_PATH;
        } else {
            scriptPath = string(spec, "script");
            if (scriptPath == null) {
                return DamogranTaskResult.failure("js task requires 'script' or inline 'code'");
            }
        }

        int timeoutSeconds = intOr(spec, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        TriggerAction.Script action = new TriggerAction.Script(
                ScriptSource.WORKSPACE, ctx.workspaceDirName(), scriptPath,
                timeoutSeconds, spec.params(), null);
        TriggerContext triggerContext = TriggerContext.standalone(
                ctx.tenantId(), ctx.projectId(), null, null, "damogran:js", ctx.processId());

        ActionResult result = actionRegistry.execute(action, triggerContext, TriggerKind.TOOL);

        if (result.outcome().isFailure()) {
            String error = result.errorMessage() != null
                    ? result.errorMessage()
                    : "js failed: " + result.outcome();
            return DamogranTaskResult.failure(error, String.valueOf(result.output()));
        }
        return DamogranTaskResult.success(resolveOutputs(spec), String.valueOf(result.output()));
    }
}
