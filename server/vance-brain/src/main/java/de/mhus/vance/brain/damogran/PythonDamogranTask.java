package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.execDeadlineSeconds;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.outputsFor;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.string;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.toResult;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Files;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code python} task: runs a Python script in the workspace via the
 * run's {@link ComposeExec} backend — either an existing {@code script} file
 * (workspace-relative) or inline {@code code} written to {@code .damogran/inline.py}.
 *
 * <p>Interpreter follows the provisioning tier: a {@code python}-type workspace
 * has a {@code .venv} (used if present), otherwise system {@code python3} (the
 * degraded tier — no isolated deps). Needs a server workspace to stage the
 * inline file and detect the venv, so it only runs on WORK (the remote runner
 * accepts {@code exec} only).
 *
 * <p>When a {@code state:} store is active for {@code python}, the body (inline
 * {@code code} or the {@code script} file's contents) is inlined into a generated
 * wrapper that deserializes a {@code state} dict from {@code cache.json},
 * prepends the {@code header}, appends the {@code footer}, and serializes
 * {@code state} back — so JSON-shaped values carry between runs of the same
 * document. See {@code planning/damogran-state.md}.
 */
@Service
class PythonDamogranTask implements DamogranTask {

    private static final String INLINE_PATH = ".damogran/inline.py";
    private static final String WRAPPER_PATH = ".damogran/state-run.py";
    private static final String VENV_PYTHON = ".venv/bin/python";

    private final DamogranStateService stateService;
    private final WorkspaceService workspaceService;

    PythonDamogranTask(DamogranStateService stateService, WorkspaceService workspaceService) {
        this.stateService = stateService;
        this.workspaceService = workspaceService;
    }

    @Override
    public String type() {
        return "python";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        DamogranStateService.StateDir sd = stateService.resolve(ctx, "python");
        String scriptPath = sd == null ? plainScriptPath(ctx, spec) : stateScriptPath(ctx, spec, sd);
        if (scriptPath == null) {
            return DamogranTaskResult.failure("python task requires 'script' or inline 'code'");
        }
        String command = pythonInterpreter(ctx) + " " + shellQuote(scriptPath);
        ComposeExec.Result result = ctx.requireExec("python").run(command, execDeadlineSeconds(spec));
        return toResult(result, command, outputsFor(ctx, spec));
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

    /** State path: inline the body into a wrapper with json load/dump around it. */
    private @Nullable String stateScriptPath(
            DamogranContext ctx, TaskSpec spec, DamogranStateService.StateDir sd) {
        String body = resolveBody(ctx, spec);
        if (body == null) {
            return null;
        }
        String wrapper = wrap(sd, body, sd.readHeader(), sd.readFooter());
        workspaceService.write(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                WRAPPER_PATH, wrapper);
        return WRAPPER_PATH;
    }

    /** The user body: inline {@code code}, or the contents of the {@code script} file. */
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

    private static String wrap(DamogranStateService.StateDir sd, String body,
                               String header, String footer) {
        String cache = DamogranStateService.jsonQuote(sd.cacheRel());
        return """
                # Damogran state wrapper (python) — generated, do not edit.
                import json as _dgs_json, os as _dgs_os
                _dgs_p = %s
                state = _dgs_json.load(open(_dgs_p, encoding="utf-8")) if _dgs_os.path.exists(_dgs_p) else {}
                # ---- header ----
                %s
                # ---- body ----
                %s
                # ---- footer ----
                %s
                # ---- serialize ----
                with open(_dgs_p, "w", encoding="utf-8") as _dgs_f:
                    _dgs_json.dump(state, _dgs_f)
                """.formatted(cache, header, body, footer);
    }

    private String pythonInterpreter(DamogranContext ctx) {
        if (ctx.workspacePath() != null && Files.isRegularFile(ctx.workspacePath().resolve(VENV_PYTHON))) {
            return VENV_PYTHON;
        }
        return "python3";
    }

    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
