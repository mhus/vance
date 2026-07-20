package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.execDeadlineSeconds;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.outputsFor;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.string;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.toResult;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Files;
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
 */
@Service
class PythonDamogranTask implements DamogranTask {

    private static final String INLINE_PATH = ".damogran/inline.py";
    private static final String VENV_PYTHON = ".venv/bin/python";

    private final WorkspaceService workspaceService;

    PythonDamogranTask(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public String type() {
        return "python";
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
                return DamogranTaskResult.failure("python task requires 'script' or inline 'code'");
            }
        }

        String command = pythonInterpreter(ctx) + " " + shellQuote(scriptPath);
        ComposeExec.Result result = ctx.requireExec("python").run(command, execDeadlineSeconds(spec));
        return toResult(result, command, outputsFor(ctx, spec));
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
