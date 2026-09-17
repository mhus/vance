package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceService#writeStream} is the byte-exact counterpart of
 * {@link WorkspaceService#readBytes}: no charset round-trip, so binary
 * payloads (parquet, zip, images) survive the copy unchanged. The regression
 * it guards against is the text-only {@code write(String)} path, where an
 * invalid UTF-8 byte silently became a three-byte U+FFFD replacement.
 */
class WorkspaceServiceWriteStreamTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "kunde-x";
    private static final String DIR = "ws";

    /** 0xFF 0xFE is not valid UTF-8 — what a binary file looks like to a decoder. */
    private static final byte[] BINARY = {0x50, 0x41, 0x52, 0x31, (byte) 0xFF, (byte) 0xFE, 0x00, 0x0D};

    private WorkspaceService serviceResolvingTo(Path file) {
        WorkspaceService service = spy(new WorkspaceService(
                mock(WorkspaceProperties.class),
                List.of(),
                mock(WorkspaceSnapshotRepository.class),
                mock(WorkspaceRootService.class)));
        doReturn(file)
                .when(service)
                .resolve(TENANT, PROJECT, DIR, file.getFileName().toString());
        return service;
    }

    @Test
    void writeStream_copiesBytesExactly_evenWhenNotUtf8(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("data.parquet");
        WorkspaceService service = serviceResolvingTo(file);

        Path written = service.writeStream(TENANT, PROJECT, DIR, "data.parquet", new ByteArrayInputStream(BINARY));

        assertThat(written).isEqualTo(file);
        assertThat(Files.readAllBytes(file)).isEqualTo(BINARY);
    }

    @Test
    void writeStream_createsMissingParentDirectories(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("nested/deep/data.bin");
        WorkspaceService service = spy(new WorkspaceService(
                mock(WorkspaceProperties.class),
                List.of(),
                mock(WorkspaceSnapshotRepository.class),
                mock(WorkspaceRootService.class)));
        doReturn(file).when(service).resolve(TENANT, PROJECT, DIR, "nested/deep/data.bin");

        service.writeStream(TENANT, PROJECT, DIR, "nested/deep/data.bin", new ByteArrayInputStream(BINARY));

        assertThat(Files.readAllBytes(file)).isEqualTo(BINARY);
    }

    @Test
    void writeStream_overwritesAnExistingFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("data.parquet");
        Files.writeString(file, "stale text content");
        WorkspaceService service = serviceResolvingTo(file);

        service.writeStream(TENANT, PROJECT, DIR, "data.parquet", new ByteArrayInputStream(BINARY));

        assertThat(Files.readAllBytes(file)).isEqualTo(BINARY);
    }

    @Test
    void writeStream_failingStream_becomesWorkspaceException(@TempDir Path tmp) {
        Path file = tmp.resolve("data.parquet");
        WorkspaceService service = serviceResolvingTo(file);
        InputStream failing = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
        };

        assertThatThrownBy(() -> service.writeStream(TENANT, PROJECT, DIR, "data.parquet", failing))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageStartingWith("Write failed:");
    }
}
