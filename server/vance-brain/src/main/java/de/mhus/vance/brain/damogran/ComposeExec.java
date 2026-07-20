package de.mhus.vance.brain.damogran;

/**
 * Per-run shell-exec backend — the one place a compose's exec mechanism differs
 * by target. WORK runs through {@link de.mhus.vance.brain.tools.exec.ExecManager}
 * (tracked jobs, live tail, hard-kill watchdog); CLIENT/DAEMON run the remote
 * host's shell through the {@code exec_run} work-target tool. Tasks and the
 * {@code git:*} import/export go through {@code ctx.exec()} without knowing which
 * backend is bound, mirroring how {@link ComposeFileIo} abstracts file IO.
 */
public interface ComposeExec {

    /**
     * Run one shell {@code command}, blocking until it finishes or is killed at
     * the deadline. A non-positive {@code deadlineSeconds} means no hard-kill —
     * run to completion (only sensible on an async run). A command or backend
     * failure is reported as a non-ok {@link Result}, not thrown, so callers can
     * halt the linear run cleanly.
     */
    Result run(String command, int deadlineSeconds);

    /**
     * Normalised outcome of a command. {@code status} is the backend's terminal
     * state ({@code COMPLETED} on a clean exit; anything else — kill/timeout —
     * counts as not-ok regardless of exit code).
     */
    record Result(String status, int exitCode, String stdout, String stderr) {

        public boolean ok() {
            return "COMPLETED".equals(status) && exitCode == 0;
        }
    }
}
