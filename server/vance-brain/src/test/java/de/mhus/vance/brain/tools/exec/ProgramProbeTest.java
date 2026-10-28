package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.process.ProcessLauncher;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgramProbeTest {

    private static final ProcessLauncher DIRECT =
            spec -> new ProcessBuilder(spec.command()).redirectErrorStream(true).start();

    @Test
    void exitZero_runs() {
        assertThat(ProgramProbe.runs(DIRECT, List.of("true"), Duration.ofSeconds(5)))
                .isTrue();
    }

    @Test
    void nonZeroExit_doesNotRun() {
        assertThat(ProgramProbe.runs(DIRECT, List.of("false"), Duration.ofSeconds(5)))
                .isFalse();
    }

    @Test
    void missingBinary_doesNotRun() {
        assertThat(ProgramProbe.runs(DIRECT, List.of("/nonexistent/ffmpeg", "-version"), Duration.ofSeconds(5)))
                .isFalse();
    }

    @Test
    void hangingProgram_hitsTheTimeout() {
        long start = System.nanoTime();

        boolean runs = ProgramProbe.runs(DIRECT, List.of("sleep", "30"), Duration.ofMillis(500));

        assertThat(runs).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void chattyProgram_doesNotBlockOnAFullPipe() {
        // ~1 MB of output, far beyond a pipe buffer.
        assertThat(ProgramProbe.runs(
                        DIRECT, List.of("sh", "-c", "head -c 1000000 /dev/zero | tr '\\0' x"), Duration.ofSeconds(10)))
                .isTrue();
    }
}
