package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.exec.ScratchArea.Scratch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScratchAreaTest {

    @TempDir
    Path tmp;

    @Test
    void open_createsAFreshDirectoryPerCall_underTheRoot() throws IOException {
        ScratchArea area = new ScratchArea(tmp.resolve("scratch"));

        try (Scratch a = area.open("ffmpeg");
                Scratch b = area.open("ffmpeg")) {
            assertThat(a.dir()).isDirectory();
            assertThat(a.dir().getParent()).isEqualTo(area.root());
            assertThat(a.dir().getFileName().toString()).startsWith("ffmpeg-");
            assertThat(a.dir()).isNotEqualTo(b.dir());
        }
    }

    @Test
    void close_removesTheDirectoryWithEverythingInside() throws IOException {
        ScratchArea area = new ScratchArea(tmp);
        Path dir;
        try (Scratch scratch = area.open("ytdlp")) {
            dir = scratch.dir();
            scratch.write("a.mp3", new byte[] {1, 2});
            Files.createDirectories(dir.resolve("nested"))
                    .resolve("x.part")
                    .toFile()
                    .createNewFile();
        }

        assertThat(dir).doesNotExist();
    }

    @Test
    void close_deletesASymlinkButNotItsTarget() throws IOException {
        Path outside = Files.writeString(tmp.resolve("keep.txt"), "keep");
        ScratchArea area = new ScratchArea(tmp.resolve("scratch"));
        try (Scratch scratch = area.open("ffmpeg")) {
            Files.createSymbolicLink(scratch.dir().resolve("link"), outside);
        }

        assertThat(outside).hasContent("keep");
    }

    @Test
    void file_rejectsNamesThatLeaveTheDirectory() throws IOException {
        try (Scratch scratch = new ScratchArea(tmp).open("whisper")) {
            assertThatThrownBy(() -> scratch.file("../x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> scratch.file("a/b")).isInstanceOf(IllegalArgumentException.class);
            assertThat(scratch.file("audio.mp3").getParent()).isEqualTo(scratch.dir());
        }
    }

    @Test
    void open_rejectsAPrefixThatIsNotALabel() {
        ScratchArea area = new ScratchArea(tmp);

        assertThatThrownBy(() -> area.open("../x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sweep_removesOnlyEntriesOlderThanTheAge() throws IOException {
        ScratchArea area = new ScratchArea(tmp);
        Path stale = Files.createDirectories(tmp.resolve("ffmpeg-old"));
        Files.writeString(stale.resolve("in.bin"), "x");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(7))));
        Path fresh = Files.createDirectories(tmp.resolve("ffmpeg-new"));

        int removed = area.sweep(Duration.ofHours(6));

        assertThat(removed).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(fresh).isDirectory();
    }

    @Test
    void sweep_withoutRoot_isANoOp() {
        assertThat(new ScratchArea(tmp.resolve("missing")).sweep(Duration.ZERO)).isZero();
    }
}
