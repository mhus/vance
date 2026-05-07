package de.mhus.vance.foot.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientExecutorTailTest {

    @Test
    void tailFile_returnsLastNLinesOldestFirst(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("stdout.log");
        Files.write(log, IntStream.rangeClosed(1, 50)
                .mapToObj(i -> "line-" + i).toList());

        List<String> tail = ClientExecutorService.tailFile(log, 5);

        assertThat(tail).containsExactly(
                "line-46", "line-47", "line-48", "line-49", "line-50");
    }

    @Test
    void tailFile_missing_returnsEmpty(@TempDir Path tmp) {
        assertThat(ClientExecutorService.tailFile(tmp.resolve("nope.log"), 10)).isEmpty();
    }

    @Test
    void toStat_capturesScalarFieldsWithoutBodies() {
        ClientExecJob job = new ClientExecJob(
                "id-1", "true",
                Path.of("stdout.log"), Path.of("stderr.log"));
        job.appendStdout("first");

        ClientExecStat s = ClientExecutorService.toStat(job);

        assertThat(s.id()).isEqualTo("id-1");
        assertThat(s.command()).isEqualTo("true");
        assertThat(s.status()).isEqualTo(ClientExecJob.Status.RUNNING);
        assertThat(s.lastOutputAt()).isAfterOrEqualTo(s.startedAt());
        assertThat(s.exitCode()).isNull();
    }
}
