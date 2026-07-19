package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.EXEC_KILL_GRACE_SECONDS;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.NO_DEADLINE_WAIT_MS;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.execDeadlineSeconds;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.fromExec;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.requireString;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.resolveOutputs;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.exec.SubmitOptions;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code exec} task: runs a shell {@code command} in the workspace via
 * {@link ExecManager}. WORK target only (CLIENT/DAEMON exec runs through the
 * {@code RemoteExecComposeRunner}). Bounded by a hard-kill deadline
 * ({@code deadlineSeconds}, alias {@code timeoutSeconds}): the run blocks until
 * the command finishes or the watchdog kills it at the deadline — a runaway
 * command is terminated and the task fails cleanly, never left orphaned.
 */
@Service
class ExecDamogranTask implements DamogranTask {

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
        int deadlineSeconds = execDeadlineSeconds(spec);
        SubmitOptions options;
        long waitMs;
        if (deadlineSeconds <= 0) {
            // deadlineSeconds: 0 = no hard-kill — run to completion (async long job).
            options = SubmitOptions.defaults();
            waitMs = NO_DEADLINE_WAIT_MS;
        } else {
            // Block slightly past the kill deadline so we observe the terminated
            // (killed) state rather than a still-RUNNING snapshot.
            options = SubmitOptions.withDeadline(Instant.now().plusSeconds(deadlineSeconds));
            waitMs = (deadlineSeconds + EXEC_KILL_GRACE_SECONDS) * 1000L;
        }
        // Surface the job id for the async run's live tail, if tracked.
        Consumer<String> onJobId = ctx.progress() == null
                ? null : jobId -> ctx.progress().execJob(jobId);

        Map<String, Object> rendered = execManager.submitTrackedAndRender(
                ctx.tenantId(), ctx.projectId(), null, ctx.processId(),
                ctx.workspaceDirName(), command, waitMs, options, onJobId);

        return fromExec(rendered, command, resolveOutputs(spec));
    }
}
