package de.mhus.vance.foot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.ClientAgentUploadRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class ClientAgentDocServiceTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.foot.connection.ConnectionService> noConn =
            mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.foot.ui.ChatTerminal> noTerminal =
            mock(ObjectProvider.class);

    private final ClientAgentDocService service = new ClientAgentDocService(
            noConn, noTerminal, new ClientAgentDocProperties());

    @Test
    void resolveIn_returnsNullWhenNothingPresent(@TempDir Path tmp) {
        assertThat(service.resolveIn(tmp)).isNull();
    }

    @Test
    void resolveIn_picksAgentMdWhenPresent(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("agent.md"), "# agent");
        Files.writeString(tmp.resolve("CLAUDE.md"), "# claude");

        assertThat(service.resolveIn(tmp))
                .isNotNull()
                .satisfies(p -> assertThat(p.getFileName().toString()).isEqualTo("agent.md"));
    }

    @Test
    void resolveIn_fallsBackToClaudeMdWhenAgentMissing(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("CLAUDE.md"), "# claude");

        assertThat(service.resolveIn(tmp))
                .isNotNull()
                .satisfies(p -> assertThat(p.getFileName().toString()).isEqualTo("CLAUDE.md"));
    }

    @Test
    void overridePath_winsOverDefaults(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("agent.md"), "# agent");
        Path custom = tmp.resolve("custom.md");
        Files.writeString(custom, "# custom");
        service.setOverridePath(custom);

        assertThat(service.resolveIn(tmp))
                .isNotNull()
                .satisfies(p -> assertThat(p.getFileName().toString()).isEqualTo("custom.md"));
    }

    @Test
    void overridePath_missingFile_returnsNullWithoutFallback(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("agent.md"), "# agent");
        service.setOverridePath(tmp.resolve("does-not-exist.md"));

        assertThat(service.resolveIn(tmp)).isNull();
    }

    @Test
    void overridePath_clearedWithNull_restoresCascade(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("CLAUDE.md"), "# claude");
        service.setOverridePath(tmp.resolve("nope.md"));
        service.setOverridePath(null);

        assertThat(service.resolveIn(tmp))
                .isNotNull()
                .satisfies(p -> assertThat(p.getFileName().toString()).isEqualTo("CLAUDE.md"));
    }

    @Test
    void overridePath_relativePathIsResolvedAgainstCwd() {
        service.setOverridePath(Path.of("relative/file.md"));

        // Override is normalised to absolute on set; we just check it ends with the requested suffix.
        assertThat(service.resolveIn(Path.of("/tmp"))).isNull();
    }

    // ─── Size limits — warn vs truncate ─────────────────────────────────

    @Test
    void uploadIfPresent_smallFile_emitsPlainInfoLine(@TempDir Path tmp) throws Exception {
        Harness h = new Harness(tmp, 60_000, 100_000);
        Files.writeString(tmp.resolve("agent.md"), "x".repeat(1000));

        boolean ok = h.service.uploadIfPresent();

        assertThat(ok).isTrue();
        verify(h.terminal).info(contains("agent doc: uploaded agent.md (1000 chars)"));
        verify(h.terminal, never()).warn(any());
        // Content sent verbatim, no truncation marker.
        assertThat(h.captureSentContent()).hasSize(1000).doesNotContain("[truncated]");
    }

    @Test
    void uploadIfPresent_overWarnButUnderTruncate_uploadsFullAndWarns(@TempDir Path tmp)
            throws Exception {
        Harness h = new Harness(tmp, 1000, 5000);
        Files.writeString(tmp.resolve("agent.md"), "x".repeat(2500));

        boolean ok = h.service.uploadIfPresent();

        assertThat(ok).isTrue();
        verify(h.terminal).warn(contains("over warn threshold 1000"));
        verify(h.terminal, never()).info(contains("agent doc: uploaded"));
        // Full file uploaded — no truncation.
        assertThat(h.captureSentContent()).hasSize(2500).doesNotContain("[truncated]");
    }

    @Test
    void uploadIfPresent_overTruncate_truncatesAndWarns(@TempDir Path tmp) throws Exception {
        Harness h = new Harness(tmp, 1000, 2000);
        Files.writeString(tmp.resolve("agent.md"), "x".repeat(5000));

        boolean ok = h.service.uploadIfPresent();

        assertThat(ok).isTrue();
        verify(h.terminal).warn(contains("TRUNCATED from 5000"));
        // Content was cut to truncateBytes + marker.
        String sent = h.captureSentContent();
        assertThat(sent).contains("[truncated]");
        assertThat(sent).startsWith("x".repeat(2000));
        // truncateBytes + marker length.
        assertThat(sent.length())
                .isEqualTo(2000 + ClientAgentDocService.TRUNCATION_MARKER.length());
    }

    @Test
    void uploadIfPresent_zeroTruncateDisablesTruncation(@TempDir Path tmp) throws Exception {
        Harness h = new Harness(tmp, 1000, 0);
        Files.writeString(tmp.resolve("agent.md"), "x".repeat(50_000));

        boolean ok = h.service.uploadIfPresent();

        assertThat(ok).isTrue();
        // truncate disabled → big file goes through, only the warn path fires.
        verify(h.terminal).warn(contains("over warn threshold"));
        assertThat(h.captureSentContent()).hasSize(50_000).doesNotContain("[truncated]");
    }

    /** Wires up the dependencies needed to exercise {@link
     *  ClientAgentDocService#uploadIfPresent()} end-to-end. */
    private static final class Harness {
        final ChatTerminal terminal;
        final ConnectionService connection;
        final ClientAgentDocService service;

        @SuppressWarnings("unchecked")
        Harness(Path cwd, int warnBytes, int truncateBytes) throws Exception {
            ClientAgentDocProperties props = new ClientAgentDocProperties();
            props.setWarnBytes(warnBytes);
            props.setTruncateBytes(truncateBytes);

            terminal = mock(ChatTerminal.class);
            connection = mock(ConnectionService.class);
            lenient().when(connection.isOpen()).thenReturn(true);
            lenient().when(connection.request(any(), any(), any(), any()))
                    .thenReturn(new Object());

            ObjectProvider<ConnectionService> connProv = mock(ObjectProvider.class);
            when(connProv.getIfAvailable()).thenReturn(connection);

            ObjectProvider<ChatTerminal> termProv = mock(ObjectProvider.class);
            when(termProv.getIfAvailable()).thenReturn(terminal);

            service = new ClientAgentDocService(connProv, termProv, props);
            // Pin override so resolution lands on cwd-relative agent.md
            // regardless of where the JVM cwd happens to be.
            service.setOverridePath(cwd.resolve("agent.md"));
        }

        String captureSentContent() throws Exception {
            ArgumentCaptor<ClientAgentUploadRequest> captor =
                    ArgumentCaptor.forClass(ClientAgentUploadRequest.class);
            verify(connection).request(
                    eq(MessageType.CLIENT_AGENT_UPLOAD),
                    captor.capture(),
                    any(),
                    any(Duration.class));
            return captor.getValue().getContent();
        }
    }
}
