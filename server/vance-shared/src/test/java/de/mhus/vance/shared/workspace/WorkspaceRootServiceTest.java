package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRootServiceTest {

    private final WorkspaceRootService svc = new WorkspaceRootService();

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
        Path p = svc.resolveWithin(base, "sub/file.txt");
        assertThat(p.startsWith(base)).isTrue();
        assertThat(p.endsWith(Path.of("sub", "file.txt"))).isTrue();
    }

    @Test
    void resolveWithin_newNestedFile_ok() {
        assertThat(svc.resolveWithin(base, "new/deep/file.txt").startsWith(base)).isTrue();
    }

    @Test
    void resolveWithin_dotDotEscape_rejected() {
        assertThatThrownBy(() -> svc.resolveWithin(base, "../outside/secret.txt"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("escapes workspace folder");
    }

    @Test
    void resolveWithin_blankOrNul_rejected() {
        assertThatThrownBy(() -> svc.resolveWithin(base, "  "))
                .isInstanceOf(WorkspaceException.class);
        assertThatThrownBy(() -> svc.resolveWithin(base, "a\0b"))
                .isInstanceOf(WorkspaceException.class).hasMessageContaining("NUL");
    }

    @Test
    void resolveWithin_dirSymlinkToOutside_rejected() throws Exception {
        Files.createSymbolicLink(base.resolve("link"), outside);

        assertThatThrownBy(() -> svc.resolveWithin(base, "link/secret.txt"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void resolveWithin_fileSymlinkToOutside_rejected() throws Exception {
        Files.createSymbolicLink(base.resolve("flink"), outside.resolve("secret.txt"));

        assertThatThrownBy(() -> svc.resolveWithin(base, "flink"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void resolveWithin_danglingSymlinkToOutside_rejected() throws Exception {
        Files.createSymbolicLink(base.resolve("dangling"), outside.resolve("missing"));

        assertThatThrownBy(() -> svc.resolveWithin(base, "dangling"))
                .isInstanceOf(WorkspaceException.class);
    }

    @Test
    void resolveWithin_symlinkInsideBase_ok() throws Exception {
        Files.createDirectories(base.resolve("sub"));
        Files.writeString(base.resolve("sub/a.txt"), "y");
        Files.createSymbolicLink(base.resolve("inlink"), base.resolve("sub"));

        assertThat(svc.resolveWithin(base, "inlink/a.txt").startsWith(base)).isTrue();
    }

    @Test
    void isWithin_reflectsContainment() throws Exception {
        Files.createSymbolicLink(base.resolve("link"), outside);

        assertThat(svc.isWithin(base, base.resolve("sub/file.txt"))).isTrue();
        assertThat(svc.isWithin(base, base.resolve("link/secret.txt"))).isFalse();
    }
}
