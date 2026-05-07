package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExecJobLastOutputAtTest {

    @Test
    void appendStdout_advancesLastOutputAt() throws InterruptedException {
        ExecJob job = newJob();
        Instant before = job.lastOutputAt();
        Thread.sleep(5);

        job.appendStdout("hello");

        assertThat(job.lastOutputAt()).isAfter(before);
    }

    @Test
    void appendStderr_advancesLastOutputAt() throws InterruptedException {
        ExecJob job = newJob();
        Instant before = job.lastOutputAt();
        Thread.sleep(5);

        job.appendStderr("boom");

        assertThat(job.lastOutputAt()).isAfter(before);
    }

    @Test
    void freshJob_lastOutputAt_equalsStartedAt() {
        ExecJob job = newJob();

        assertThat(job.lastOutputAt()).isEqualTo(job.startedAt());
    }

    private ExecJob newJob() {
        return new ExecJob(
                "id-1", "proj-1", "echo hi",
                Path.of("stdout.log"), Path.of("stderr.log"));
    }
}
