package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RootDirFilesTest {

    /** 0xFF 0xFE is not valid UTF-8 — what a binary file looks like to a decoder. */
    private static final byte[] BINARY = {0x50, 0x41, 0x52, 0x31, (byte) 0xFF, (byte) 0xFE, 0x00, 0x0D};

    @TempDir
    Path root;

    @Test
    void write_createsParentsAndOverwrites() {
        Path file = root.resolve("a/b/c.txt");

        RootDirFiles.write(file, "one");
        RootDirFiles.write(file, null);

        assertThat(file).hasContent("");
    }

    @Test
    void writeStream_copiesBytesExactly() throws Exception {
        Path file = root.resolve("nested/data.parquet");

        RootDirFiles.writeStream(file, new ByteArrayInputStream(BINARY));

        assertThat(Files.readAllBytes(file)).isEqualTo(BINARY);
    }

    @Test
    void read_truncatesToMaxChars() throws Exception {
        Path file = Files.writeString(root.resolve("t.txt"), "abcdef");

        RootDirFiles.ReadResult r = RootDirFiles.read(file, "t.txt", 4);

        assertThat(r.text()).isEqualTo("abcd");
        assertThat(r.truncated()).isTrue();
        assertThat(r.totalChars()).isEqualTo(6);
    }

    @Test
    void read_missingOrDirectory_saysSo() throws Exception {
        Files.createDirectories(root.resolve("d"));

        assertThatThrownBy(() -> RootDirFiles.read(root.resolve("nope"), "nope", 0))
                .isInstanceOf(RootDirException.class)
                .hasMessage("Not found: nope");
        assertThatThrownBy(() -> RootDirFiles.read(root.resolve("d"), "d", 0))
                .isInstanceOf(RootDirException.class)
                .hasMessage("Not a regular file: d");
    }

    @Test
    void readLines_returnsTheWindow() throws Exception {
        Path file = Files.writeString(root.resolve("l.txt"), "one\ntwo\nthree\nfour\n", StandardCharsets.UTF_8);

        assertThat(RootDirFiles.readLines(file, "l.txt", 0, 2, 2).text()).isEqualTo("two\nthree");
        assertThat(RootDirFiles.readLines(file, "l.txt", 0, 0, 0).text()).isEqualTo("one\ntwo\nthree\nfour");
        assertThat(RootDirFiles.readLines(file, "l.txt", 3, 1, 10).truncated()).isTrue();
    }

    @Test
    void readLines_undecodableFile_failsLikeTheWholeFileRead() throws Exception {
        Path file =
                Files.write(root.resolve("latin1.txt"), new byte[] {'h', 'i', '\n', (byte) 0xFF, (byte) 0xFE, '\n'});

        Throwable windowed = org.assertj.core.api.Assertions.catchThrowable(
                () -> RootDirFiles.readLines(file, "latin1.txt", 0, 1, 10));
        Throwable whole =
                org.assertj.core.api.Assertions.catchThrowable(() -> RootDirFiles.read(file, "latin1.txt", 0));

        assertThat(windowed).isInstanceOf(RootDirException.class).hasMessageStartingWith("Read failed:");
        assertThat(windowed.getMessage()).isEqualTo(whole.getMessage());
    }

    @Test
    void list_isRecursiveRelativeAndSorted() throws Exception {
        Files.createDirectories(root.resolve("z/y"));
        Files.writeString(root.resolve("z/y/f.txt"), "x");
        Files.writeString(root.resolve("a.txt"), "x");

        assertThat(RootDirFiles.list(root))
                .containsExactly("a.txt", Path.of("z", "y", "f.txt").toString());
    }

    @Test
    void delete_removesFiles_butRefusesDirectories() throws Exception {
        Path file = Files.writeString(root.resolve("f.txt"), "x");
        Files.createDirectories(root.resolve("d"));

        assertThat(RootDirFiles.delete(file, "f.txt")).isTrue();
        assertThat(RootDirFiles.delete(file, "f.txt")).isFalse();
        assertThatThrownBy(() -> RootDirFiles.delete(root.resolve("d"), "d"))
                .isInstanceOf(RootDirException.class)
                .hasMessage("Refusing to delete a directory: d");
    }

    @Test
    void readBytes_enforcesTheSizeLimit() throws Exception {
        Path file = Files.write(root.resolve("big.bin"), new byte[100]);

        assertThat(RootDirFiles.readBytes(file, "big.bin", 0)).hasSize(100);
        assertThatThrownBy(() -> RootDirFiles.readBytes(file, "big.bin", 10))
                .isInstanceOfSatisfying(RootDirFileSizeExceededException.class, e -> {
                    assertThat(e.getFileSize()).isEqualTo(100);
                    assertThat(e.getLimit()).isEqualTo(10);
                });
    }

    @Test
    void requireRegularFile_rejectsDirectories() throws Exception {
        Files.createDirectories(root.resolve("d"));

        assertThatThrownBy(() -> RootDirFiles.requireRegularFile(root.resolve("d"), "d"))
                .isInstanceOf(RootDirException.class);
    }

    @Test
    void dirNode_listsToTheGivenDepth_sorted_withoutSymlinks() throws Exception {
        Files.createDirectories(root.resolve("sub/deeper"));
        Files.writeString(root.resolve("sub/deeper/x.txt"), "x");
        Files.writeString(root.resolve("b.txt"), "bb");
        Files.writeString(root.resolve("a.txt"), "a");
        Path outside = Files.createDirectories(root.resolveSibling(root.getFileName() + "-outside"));
        Files.writeString(outside.resolve("secret.txt"), "s");
        Files.createSymbolicLink(root.resolve("link"), outside);

        TreeNode top = RootDirFiles.dirNode("ws", "ws", root, 1);

        assertThat(top.children()).extracting(TreeNode::name).containsExactly("a.txt", "b.txt", "sub");
        TreeNode sub = top.children().get(2);
        assertThat(sub.directory()).isTrue();
        assertThat(sub.children()).isNull();
        assertThat(top.children().get(1).size()).isEqualTo(2);
        assertThat(RootDirFiles.dirNode("ws", "ws", root, 3).children().get(2).children())
                .extracting(TreeNode::path)
                .containsExactly("ws/sub/deeper");
    }

    @Test
    void dirNode_withNoDepth_hasNoChildren() {
        assertThat(RootDirFiles.dirNode("ws", "ws", root, 0).children()).isNull();
    }

    @Test
    void fileNode_carriesSizeAndMtime() throws Exception {
        Path file = Files.writeString(root.resolve("f.txt"), "abc");

        TreeNode node = RootDirFiles.fileNode("f.txt", "ws/f.txt", file);

        assertThat(node.directory()).isFalse();
        assertThat(node.size()).isEqualTo(3);
        assertThat(node.lastModified()).isNotNull();
        assertThat(List.of(node.name(), node.path())).containsExactly("f.txt", "ws/f.txt");
    }
}
