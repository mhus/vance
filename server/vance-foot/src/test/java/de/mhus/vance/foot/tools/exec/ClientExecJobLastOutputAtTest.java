package de.mhus.vance.foot.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ClientExecJobLastOutputAtTest {

    @Test
    void appendStdout_advancesLastOutputAt() throws InterruptedException {
        ClientExecJob job = newJob();
        Instant before = job.lastOutputAt();
        Thread.sleep(5);

        job.appendStdout("hello");

        assertThat(job.lastOutputAt()).isAfter(before);
    }

    @Test
    void appendStderr_advancesLastOutputAt() throws InterruptedException {
        ClientExecJob job = newJob();
        Instant before = job.lastOutputAt();
        Thread.sleep(5);

        job.appendStderr("boom");

        assertThat(job.lastOutputAt()).isAfter(before);
    }

    @Test
    void freshJob_lastOutputAt_equalsStartedAt() {
        ClientExecJob job = newJob();

        assertThat(job.lastOutputAt()).isEqualTo(job.startedAt());
    }

    private ClientExecJob newJob() {
        return new ClientExecJob(
                "id-1", "echo hi",
                Path.of("stdout.log"), Path.of("stderr.log"));
    }
}
