package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code exec} task: runs a shell {@code command} on the run's
 * {@link ComposeExec} backend. Bounded by a hard-kill deadline
 * ({@code deadlineSeconds}, alias {@code timeoutSeconds}, {@code 0} = no kill):
 * the run blocks until the command finishes or the watchdog kills it — a runaway
 * command is terminated and the task fails cleanly, never left orphaned. The
 * exec mechanism (WORK {@code ExecManager} vs. remote {@code exec_run}) lives in
 * the backend, so the task itself is target-agnostic.
 */
@Service
class ExecDamogranTask implements DamogranTask {

    @Override
    public String type() {
        return "exec";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        return DamogranTaskSupport.runExecTask(ctx, spec);
    }
}
