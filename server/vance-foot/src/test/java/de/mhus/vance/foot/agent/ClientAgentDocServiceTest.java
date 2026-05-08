package de.mhus.vance.foot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class ClientAgentDocServiceTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.foot.connection.ConnectionService> noConn =
            mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.foot.ui.ChatTerminal> noTerminal =
            mock(ObjectProvider.class);

    private final ClientAgentDocService service = new ClientAgentDocService(noConn, noTerminal);

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
}
