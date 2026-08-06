package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /attach} — staging, listing, clearing, and bad input. */
class AttachSlashCommandTest {

    @TempDir
    Path tempDir;

    private PendingAttachmentService pending;
    private AttachSlashCommand command;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        pending = new PendingAttachmentService();
        command = new AttachSlashCommand(pending, mock(ChatTerminal.class));
        file = Files.writeString(tempDir.resolve("shot.png"), "data");
    }

    @Test
    void pathArgument_stagesTheFile() {
        command.execute(List.of(file.toString()));

        assertThat(pending.count()).isEqualTo(1);
    }

    @Test
    void repeatedCalls_stageSeveralFiles() throws IOException {
        Path second = Files.writeString(tempDir.resolve("b.txt"), "b");

        command.execute(List.of(file.toString()));
        command.execute(List.of(second.toString()));

        assertThat(pending.count()).isEqualTo(2);
    }

    @Test
    void pathWithSpaces_isJoinedBackTogether() throws IOException {
        // The dispatcher splits on whitespace; a desktop path routinely
        // contains spaces, so the tokens have to be rejoined.
        Path spaced = Files.writeString(tempDir.resolve("my shot.png"), "data");

        command.execute(List.of(spaced.toString().split(" ")));

        assertThat(pending.staged()).containsExactly(spaced.toAbsolutePath().normalize());
    }

    @Test
    void clear_dropsTheQueue() {
        command.execute(List.of(file.toString()));

        command.execute(List.of("clear"));

        assertThat(pending.isEmpty()).isTrue();
    }

    @Test
    void noArguments_listsWithoutChangingAnything() {
        command.execute(List.of(file.toString()));

        command.execute(List.of());

        assertThat(pending.count()).isEqualTo(1);
    }

    @Test
    void missingFile_doesNotStage_andDoesNotThrow() {
        command.execute(List.of(tempDir.resolve("nope.png").toString()));

        assertThat(pending.isEmpty()).isTrue();
    }
}
