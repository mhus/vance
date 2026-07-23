package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the in-memory stdout/stderr buffer cap (code-review Phase 2): a
 * chatty/long-running job must not grow the heap without bound. Only the
 * oldest overflow is dropped; the most recent output is retained (the full
 * stream stays in the on-disk log for tail).
 */
class ExecJobBufferCapTest {

    private ExecJob newJob() {
        return new ExecJob("id-1", "proj-1", "noisy",
                Path.of("stdout.log"), Path.of("stderr.log"));
    }

    @Test
    void stdout_isCapped_keepingMostRecentOutput() {
        ExecJob job = newJob();
        // ~2 MB of output across many lines — far past the 64 KB cap.
        for (int i = 0; i < 20_000; i++) {
            job.appendStdout("line-" + i + " with some padding text to add bulk");
        }
        job.appendStdout("FINAL-MARKER");

        String out = job.readStdout();

        assertThat(out.length()).isLessThanOrEqualTo(64 * 1024 + 64);
        // Newest content survives; the earliest lines were trimmed.
        assertThat(out).endsWith("FINAL-MARKER\n");
        assertThat(out).doesNotContain("line-0 ");
    }

    @Test
    void stderr_isCapped() {
        ExecJob job = newJob();
        for (int i = 0; i < 20_000; i++) {
            job.appendStderr("err-" + i + " padding padding padding padding");
        }

        assertThat(job.readStderr().length()).isLessThanOrEqualTo(64 * 1024 + 64);
    }
}
