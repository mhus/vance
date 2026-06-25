package de.mhus.vance.brain.tools.exec;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Projects an {@link ExecJob} onto the flat map shape tools return to
 * the LLM. Centralised so {@code work_exec_run} and {@code work_exec_status}
 * agree on field names and truncation rules.
 */
final class ExecJobRenderer {

    private ExecJobRenderer() {}

    static Map<String, Object> render(ExecJob job, int inlineCap) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.id());
        out.put("status", job.status().name());
        out.put("command", job.command());
        Instant end = job.finishedAt() != null ? job.finishedAt() : Instant.now();
        out.put("durationMs", Duration.between(job.startedAt(), end).toMillis());
        out.put("lastOutputAt", job.lastOutputAt().toString());
        if (job.exitCode() != null) {
            out.put("exitCode", job.exitCode());
        }
        out.put("stdoutPath", job.stdoutFile().toString());
        out.put("stderrPath", job.stderrFile().toString());

        String stdout = job.readStdout();
        String stderr = job.readStderr();
        out.put("stdout", truncate(stdout, inlineCap));
        out.put("stderr", truncate(stderr, inlineCap));
        if (stdout.length() > inlineCap || stderr.length() > inlineCap) {
            out.put("truncated", true);
            out.put("hint",
                    "Output truncated. Use another work_exec_run with bounded commands "
                            + "(head -N / tail -N / sed -n 'A,Bp' <path> / grep -m N) "
                            + "against stdoutPath/stderrPath to page through.");
        }
        return out;
    }

    /**
     * Head/tail split for shell-style output: 20 % head + 80 % tail with
     * a sentinel between. Shell errors/exit info sit at the end, so a
     * pure HEAD cut would hide the relevant lines from the LLM.
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int headBudget = max / 5;
        int tailBudget = max - headBudget;
        int omitted = s.length() - headBudget - tailBudget;
        return s.substring(0, headBudget)
                + "\n…[truncated, " + omitted + " chars omitted]\n"
                + s.substring(s.length() - tailBudget);
    }
}
