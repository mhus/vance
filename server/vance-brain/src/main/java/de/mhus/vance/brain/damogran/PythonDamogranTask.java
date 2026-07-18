package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.fromExec;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.intOr;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.resolveOutputs;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.string;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Files;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code python} task: runs a Python script in the workspace via
 * {@link ExecManager} — either an existing {@code script} file (workspace-
 * relative) or inline {@code code} written to {@code .damogran/inline.py}.
 *
 * <p>Interpreter follows the provisioning tier: a {@code python}-type workspace
 * has a {@code .venv} (used if present), otherwise system {@code python3}
 * (the degraded tier — no isolated deps). WORK target only in v1.
 */
@Service
class PythonDamogranTask implements DamogranTask {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final String INLINE_PATH = ".damogran/inline.py";
    private static final String VENV_PYTHON = ".venv/bin/python";

    private final ExecManager execManager;
    private final WorkspaceService workspaceService;

    PythonDamogranTask(ExecManager execManager, WorkspaceService workspaceService) {
        this.execManager = execManager;
        this.workspaceService = workspaceService;
    }

    @Override
    public String type() {
        return "python";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        if (!ctx.isWork()) {
            return DamogranTaskResult.failure("python task requires target WORK (v1)");
        }

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

        String interpreter = pythonInterpreter(ctx);
        String command = interpreter + " " + shellQuote(scriptPath);
        long waitMs = intOr(spec, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS) * 1000L;

        Map<String, Object> rendered = execManager.submitTrackedAndRender(
                ctx.tenantId(), ctx.projectId(), null, ctx.processId(),
                ctx.workspaceDirName(), command, waitMs);

        return fromExec(rendered, command, resolveOutputs(spec));
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
