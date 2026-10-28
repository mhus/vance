package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ProcessOutputTest {

    @Test
    void aFinishedProcessYieldsItsOutputAndExitCode() throws Exception {
        Process p = new ProcessBuilder("sh", "-c", "echo hello; exit 3")
                .redirectErrorStream(true)
                .start();

        ProcessOutput.Result result = ProcessOutput.await(p, 10);

        assertThat(result.text()).isEqualTo("hello\n");
        assertThat(result.exitValue()).isEqualTo(3);
    }

    @Test
    void aHungProcessThatKeepsItsOutputOpenStillTimesOut() throws Exception {
        // Prints nothing and never exits: reading to EOF first would block for the full 30 s.
        Process p = new ProcessBuilder("sh", "-c", "sleep 30")
                .redirectErrorStream(true)
                .start();
        long start = System.nanoTime();

        assertThatThrownBy(() -> ProcessOutput.await(p, 1)).isInstanceOf(TimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
}
