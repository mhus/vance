package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceService#readLines} has to fail the same way its sibling
 * {@link WorkspaceService#read} does. {@code Files.lines} reports a decoding
 * failure while the stream is <em>consumed</em>, wrapped in an
 * {@code UncheckedIOException} — so the {@code catch (IOException)} that works
 * for {@code Files.readString} does not catch it, and the same file left
 * through two different exception types depending on whether a line window was
 * asked for.
 */
class WorkspaceServiceReadLinesTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "kunde-x";
    private static final String DIR = "ws";

    private WorkspaceService serviceResolvingTo(Path file) {
        WorkspaceService service = spy(new WorkspaceService(
                mock(WorkspaceProperties.class),
                List.of(),
                mock(WorkspaceSnapshotRepository.class),
                mock(WorkspaceRootService.class)));
        doReturn(file).when(service).resolve(TENANT, PROJECT, DIR, file.getFileName().toString());
        return service;
    }

    @Test
    void readLines_undecodableFile_failsAsWorkspaceException(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("latin1.txt");
        // 0xFF 0xFE is not valid UTF-8 — a Latin-1 or binary file in the
        // workspace looks exactly like this.
        Files.write(file, new byte[]{'h', 'i', '\n', (byte) 0xFF, (byte) 0xFE, '\n'});
        WorkspaceService service = serviceResolvingTo(file);

        assertThatThrownBy(() ->
                service.readLines(TENANT, PROJECT, DIR, "latin1.txt", 0, 1, 10))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageStartingWith("Read failed:");
    }

    @Test
    void readLines_undecodableFile_failsLikeTheWholeFileRead(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("latin1.txt");
        Files.write(file, new byte[]{'h', 'i', '\n', (byte) 0xFF, (byte) 0xFE, '\n'});
        WorkspaceService service = serviceResolvingTo(file);

        String windowed = catchMessage(() ->
                service.readLines(TENANT, PROJECT, DIR, "latin1.txt", 0, 1, 10));
        String whole = catchMessage(() ->
                service.read(TENANT, PROJECT, DIR, "latin1.txt", 0));

        assertThat(windowed).isEqualTo(whole);
    }

    @Test
    void readLines_returnsTheRequestedWindow(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("plain.txt");
        Files.writeString(file, "one\ntwo\nthree\nfour\n", StandardCharsets.UTF_8);
        WorkspaceService service = serviceResolvingTo(file);

        WorkspaceService.ReadResult r =
                service.readLines(TENANT, PROJECT, DIR, "plain.txt", 0, 2, 2);

        assertThat(r.text()).isEqualTo("two\nthree");
        assertThat(r.truncated()).isFalse();
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected a WorkspaceException");
        } catch (WorkspaceException e) {
            return e.getMessage();
        }
    }
}
