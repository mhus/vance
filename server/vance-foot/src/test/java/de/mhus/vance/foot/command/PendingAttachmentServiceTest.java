package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Staging queue behind {@code /attach}. The contract that matters: a
 * file rides on exactly one turn, and a bad path is rejected where the
 * user typed it rather than at send time.
 */
class PendingAttachmentServiceTest {

    @TempDir
    Path tempDir;

    private PendingAttachmentService pending;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        pending = new PendingAttachmentService();
        file = Files.writeString(tempDir.resolve("shot.png"), "not really a png");
    }

    @Test
    void stagedFile_isListedAndCounted() {
        pending.stage(file);

        assertThat(pending.staged()).containsExactly(file.toAbsolutePath().normalize());
        assertThat(pending.count()).isEqualTo(1);
        assertThat(pending.isEmpty()).isFalse();
    }

    @Test
    void missingFile_isRejectedImmediately() {
        assertThatThrownBy(() -> pending.stage(tempDir.resolve("nope.png")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no such file");
    }

    @Test
    void directory_isRejected() {
        assertThatThrownBy(() -> pending.stage(tempDir))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void drain_emptiesTheQueue_soAFileRidesOnOneTurnOnly() {
        pending.stage(file);

        List<Path> first = pending.drain();

        assertThat(first).hasSize(1);
        assertThat(pending.isEmpty()).isTrue();
        assertThat(pending.drain()).isEmpty();
    }

    @Test
    void clear_reportsHowManyWereDiscarded() {
        pending.stage(file);
        pending.stage(file);

        assertThat(pending.clear()).isEqualTo(2);
        assertThat(pending.isEmpty()).isTrue();
    }

    @Test
    void severalFiles_keepTheirOrder() throws IOException {
        Path second = Files.writeString(tempDir.resolve("b.txt"), "b");
        pending.stage(file);
        pending.stage(second);

        assertThat(pending.staged())
                .containsExactly(file.toAbsolutePath().normalize(),
                        second.toAbsolutePath().normalize());
    }
}
