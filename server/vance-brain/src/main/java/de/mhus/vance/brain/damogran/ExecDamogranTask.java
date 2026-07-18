package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.fromExec;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.intOr;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.requireString;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.resolveOutputs;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.tools.exec.ExecManager;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code exec} task: runs a shell {@code command} in the workspace via
 * {@link ExecManager}, synchronously with a deadline. WORK target only in v1
 * (CLIENT/DAEMON routing via the {@code exec_run} dispatcher is deferred).
 */
@Service
class ExecDamogranTask implements DamogranTask {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private final ExecManager execManager;

    ExecDamogranTask(ExecManager execManager) {
        this.execManager = execManager;
    }

    @Override
    public String type() {
        return "exec";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        if (!ctx.isWork()) {
            return DamogranTaskResult.failure("exec task requires target WORK (v1)");
        }
        String command = requireString(spec, "command");
        long waitMs = intOr(spec, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS) * 1000L;

        Map<String, Object> rendered = execManager.submitTrackedAndRender(
                ctx.tenantId(), ctx.projectId(), null, ctx.processId(),
                ctx.workspaceDirName(), command, waitMs);

        return fromExec(rendered, command, resolveOutputs(spec));
    }
}
