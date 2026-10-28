package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.exec.ExecJob;
import de.mhus.vance.exec.ExecJobs;
import de.mhus.vance.exec.ExecStat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecManagerTailTest {

    @Test
    void tailFile_returnsLastNLinesOldestFirst(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("stdout.log");
        Files.write(log, IntStream.rangeClosed(1, 50).mapToObj(i -> "line-" + i).toList());

        List<String> tail = ExecJobs.tailFile(log, 5);

        assertThat(tail).containsExactly("line-46", "line-47", "line-48", "line-49", "line-50");
    }

    @Test
    void tailFile_smallerThanN_returnsAll(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("stdout.log");
        Files.write(log, List.of("only-line"));

        assertThat(ExecJobs.tailFile(log, 10)).containsExactly("only-line");
    }

    @Test
    void tailFile_missing_returnsEmpty(@TempDir Path tmp) {
        assertThat(ExecJobs.tailFile(tmp.resolve("nope.log"), 10)).isEmpty();
    }

    @Test
    void toStat_capturesScalarFieldsWithoutBodies() {
        ExecJob job = new ExecJob("id-1", "proj-x", "true", Path.of("stdout.log"), Path.of("stderr.log"));
        job.appendStdout("first");
        job.appendStderr("oops");

        ExecStat s = ExecJobs.toStat(job);

        assertThat(s.id()).isEqualTo("id-1");
        assertThat(s.projectId()).isEqualTo("proj-x");
        assertThat(s.command()).isEqualTo("true");
        assertThat(s.status()).isEqualTo(ExecJob.Status.RUNNING);
        assertThat(s.lastOutputAt()).isAfterOrEqualTo(s.startedAt());
        assertThat(s.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(s.exitCode()).isNull();
        assertThat(s.finishedAt()).isNull();
    }
}
