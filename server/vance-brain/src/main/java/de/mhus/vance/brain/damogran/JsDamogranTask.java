package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.intOr;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.resolveOutputs;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.string;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.api.action.TriggerKind;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code js} task: runs a workspace JavaScript file via the shared
 * {@link ActionExecutorRegistry} ({@link ScriptSource#WORKSPACE}) — either an
 * existing {@code script} file or inline {@code code} written to
 * {@code .damogran/inline.js}. The script's return value is captured in the
 * result log.
 *
 * <p>Note: server-side JS runs with no writable filesystem, so state is
 * <b>handler-mediated</b>: when a {@code state:} store is active for {@code js},
 * the handler reads {@code cache.json}, injects it as a {@code state} literal,
 * wraps {@code header}/body/{@code footer} in an IIFE, and has the script return
 * {@code JSON.stringify(state)} — which the handler writes back to
 * {@code cache.json}. Consequence (v1): in state mode the task's output <em>is</em>
 * the serialized state (a separate log channel is future work). WORK target only.
 * See {@code planning/damogran-state.md}.
 */
@Service
class JsDamogranTask implements DamogranTask {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final String INLINE_PATH = ".damogran/inline.js";
    private static final String WRAPPER_PATH = ".damogran/state-run.js";

    private final ActionExecutorRegistry actionRegistry;
    private final WorkspaceService workspaceService;
    private final DamogranStateService stateService;

    JsDamogranTask(ActionExecutorRegistry actionRegistry, WorkspaceService workspaceService,
                   DamogranStateService stateService) {
        this.actionRegistry = actionRegistry;
        this.workspaceService = workspaceService;
        this.stateService = stateService;
    }

    @Override
    public String type() {
        return "js";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        DamogranStateService.StateDir sd = stateService.resolve(ctx, "js");
        String scriptPath = sd == null
                ? plainScriptPath(ctx, spec)
                : stateScriptPath(ctx, spec, sd);
        if (scriptPath == null) {
            return DamogranTaskResult.failure("js task requires 'script' or inline 'code'");
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
        String output = String.valueOf(result.output());
        if (sd != null) {
            // Serialize the returned state (JSON.stringify(state)) back to cache.
            workspaceService.write(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                    sd.cacheRel(), output);
        }
        return DamogranTaskResult.success(resolveOutputs(spec), output);
    }

    /** Plain (stateless) path: inline code → file, or the given script path. */
    private @Nullable String plainScriptPath(DamogranContext ctx, TaskSpec spec) {
        String code = string(spec, "code");
        if (code != null) {
            workspaceService.write(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                    INLINE_PATH, code);
            return INLINE_PATH;
        }
        return string(spec, "script");
    }

    /** State path: build the IIFE wrapper with the cache injected + returned. */
    private @Nullable String stateScriptPath(
            DamogranContext ctx, TaskSpec spec, DamogranStateService.StateDir sd) {
        String body = resolveBody(ctx, spec);
        if (body == null) {
            return null;
        }
        String cache = sd.readCache();
        String initExpr = cache.isBlank() ? "{}" : cache;
        String wrapper = wrap(initExpr, sd.readHeader(), body, sd.readFooter());
        workspaceService.write(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                WRAPPER_PATH, wrapper);
        return WRAPPER_PATH;
    }

    private @Nullable String resolveBody(DamogranContext ctx, TaskSpec spec) {
        String code = string(spec, "code");
        if (code != null) {
            return code;
        }
        String scriptPath = string(spec, "script");
        if (scriptPath == null) {
            return null;
        }
        return workspaceService.read(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                scriptPath, 0).text();
    }

    private static String wrap(String initExpr, String header, String body, String footer) {
        // cache.json content is valid JSON, hence a valid JS object-literal expression.
        return """
                // Damogran state wrapper (js) — generated, do not edit.
                let state = %s;
                (function () {
                %s
                %s
                %s
                })();
                JSON.stringify(state);
                """.formatted(initExpr, header, body, footer);
    }
}
