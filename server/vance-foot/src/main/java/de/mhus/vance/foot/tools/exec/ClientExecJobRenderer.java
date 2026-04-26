package de.mhus.vance.foot.tools.exec;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Projects a {@link ClientExecJob} onto the flat map shape returned
 * to the brain. Centralised so {@code client_exec_run} and
 * {@code client_exec_status} agree on field names and truncation.
 */
final class ClientExecJobRenderer {

    private static final int INLINE_CHAR_CAP = 8_000;

    private ClientExecJobRenderer() {}

    static Map<String, Object> render(ClientExecJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.id());
        out.put("status", job.status().name());
        out.put("command", job.command());
        Instant end = job.finishedAt() != null ? job.finishedAt() : Instant.now();
        out.put("durationMs", Duration.between(job.startedAt(), end).toMillis());
        if (job.exitCode() != null) {
            out.put("exitCode", job.exitCode());
        }
        out.put("stdoutPath", job.stdoutFile().toString());
        out.put("stderrPath", job.stderrFile().toString());

        String stdout = job.readStdout();
        String stderr = job.readStderr();
        out.put("stdout", truncate(stdout));
        out.put("stderr", truncate(stderr));
        if (stdout.length() > INLINE_CHAR_CAP || stderr.length() > INLINE_CHAR_CAP) {
            out.put("truncated", true);
            out.put("hint",
                    "Output truncated. Re-run with bounded shell commands "
                            + "(head -N / tail -N / sed -n 'A,Bp' <path>) "
                            + "against stdoutPath/stderrPath.");
        }
        return out;
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= INLINE_CHAR_CAP) return s == null ? "" : s;
        return s.substring(0, INLINE_CHAR_CAP) + "\n…[truncated, "
                + (s.length() - INLINE_CHAR_CAP) + " more chars]";
    }
}
