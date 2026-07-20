package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.EXEC_KILL_GRACE_SECONDS;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.NO_DEADLINE_WAIT_MS;

import de.mhus.vance.brain.tools.ContextToolsApi;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLIENT/DAEMON {@link ComposeExec}: runs the command on the remote host via the
 * {@code exec_run} work-target tool (routed to the connected Foot / named daemon
 * because the process WorkTarget is set). Blocks past the hard-kill deadline
 * (which the remote enforces) so the run never returns while the command is
 * still going and races the next step. No live tail here — the exec happens off
 * this pod.
 */
final class RemoteComposeExec implements ComposeExec {

    private final ContextToolsApi tools;

    RemoteComposeExec(ContextToolsApi tools) {
        this.tools = tools;
    }

    @Override
    public Result run(String command, int deadlineSeconds) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("command", command);
        params.put("deadlineSeconds", deadlineSeconds);
        params.put("waitMs", deadlineSeconds <= 0
                ? NO_DEADLINE_WAIT_MS
                : (deadlineSeconds + EXEC_KILL_GRACE_SECONDS) * 1000L);

        Map<String, Object> out;
        try {
            out = tools.invoke("exec_run", params);
        } catch (RuntimeException e) {
            // A remote-dispatch failure (host gone, tool error) is a task
            // failure, not a crash — normalise it to a non-ok result so the
            // linear run halts cleanly (the remote runner calls this directly,
            // outside the task-executor's catch).
            return new Result("ERROR", -1, "", "remote exec failed: " + e.getMessage());
        }
        int exit = out.get("exitCode") instanceof Number n ? n.intValue() : -1;
        // The remote tool ran the command to completion within the call (a
        // deadline kill surfaces as a non-zero exit, which ok() already rejects).
        return new Result("COMPLETED", exit, output(out), "");
    }

    private static String output(Map<String, Object> out) {
        Object o = out.get("output");
        if (o == null) {
            o = out.get("stdout");
        }
        return o == null ? "" : o.toString();
    }
}
