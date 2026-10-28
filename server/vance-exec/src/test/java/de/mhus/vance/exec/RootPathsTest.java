package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RootPathsTest {

    @TempDir
    Path tmp;

    private Path base;
    private Path outside;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createDirectories(tmp.resolve("ws"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
    }

    @Test
    void resolveWithin_normalRelativePath_ok() {
        Path p = RootPaths.resolveWithin(base, "sub/file.txt");
        assertThat(p.startsWith(base)).isTrue();
        assertThat(p.endsWith(Path.of("sub", "file.txt"))).isTrue();
    }

    @Test
    void resolveWithin_newNestedFile_ok() {
        assertThat(RootPaths.resolveWithin(base, "new/deep/file.txt").startsWith(base))
                .isTrue();
    }

    @Test
    void resolveWithin_dotDotEscape_rejected() {
        assertThatThrownBy(() -> RootPaths.resolveWithin(base, "../outside/secret.txt"))
                .isInstanceOf(RootDirException.class)
                .hasMessageContaining("escapes workspace folder");
    }

    @Test
    void resolveWithin_blankOrNul_rejected() {
        assertThatThrownBy(() -> RootPaths.resolveWithin(base, "  ")).isInstanceOf(RootDirException.class);
        assertThatThrownBy(() -> RootPaths.resolveWithin(base, "a\0b"))
                .isInstanceOf(RootDirException.class)
                .hasMessageContaining("NUL");
    }

    @Test
    void resolveWithin_dirSymlinkToOutside_rejected() throws Exception {
        Files.createSymbolicLink(base.resolve("link"), outside);

        assertThatThrownBy(() -> RootPaths.resolveWithin(base, "link/secret.txt"))
                .isInstanceOf(RootDirException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void resolveWithin_fileSymlinkToOutside_rejected() throws Exception {
        Files.createSymbolicLink(base.resolve("flink"), outside.resolve("secret.txt"));

        assertThatThrownBy(() -> RootPaths.resolveWithin(base, "flink"))
                .isInstanceOf(RootDirException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void resolveWithin_danglingSymlinkToOutside_rejected() throws Exception {
        Files.createSymbolicLink(base.resolve("dangling"), outside.resolve("missing"));

        assertThatThrownBy(() -> RootPaths.resolveWithin(base, "dangling")).isInstanceOf(RootDirException.class);
    }

    @Test
    void resolveWithin_symlinkInsideBase_ok() throws Exception {
        Files.createDirectories(base.resolve("sub"));
        Files.writeString(base.resolve("sub/a.txt"), "y");
        Files.createSymbolicLink(base.resolve("inlink"), base.resolve("sub"));

        assertThat(RootPaths.resolveWithin(base, "inlink/a.txt").startsWith(base))
                .isTrue();
    }

    @Test
    void isWithin_reflectsContainment() throws Exception {
        Files.createSymbolicLink(base.resolve("link"), outside);

        assertThat(RootPaths.isWithin(base, base.resolve("sub/file.txt"))).isTrue();
        assertThat(RootPaths.isWithin(base, base.resolve("link/secret.txt"))).isFalse();
    }
}
