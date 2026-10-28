package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The confinement rules are tested in {@code vance-exec} ({@code RootPathsTest});
 * this pins the Spring facade's one job: the plain-Java exception becomes a
 * {@link WorkspaceException}.
 */
class WorkspaceRootServiceTest {

    private final WorkspaceRootService svc = new WorkspaceRootService();

    @TempDir
    Path tmp;

    @Test
    void resolveWithin_insideTheBase_returnsThePath() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("ws"));

        assertThat(svc.resolveWithin(base, "sub/file.txt")).isEqualTo(base.resolve("sub/file.txt"));
    }

    @Test
    void resolveWithin_escape_isAWorkspaceException() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("ws"));

        assertThatThrownBy(() -> svc.resolveWithin(base, "../outside"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("escapes workspace folder")
                .hasCauseInstanceOf(de.mhus.vance.exec.RootDirException.class);
    }

    @Test
    void isWithin_delegates() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("ws"));

        assertThat(svc.isWithin(base, base.resolve("a.txt"))).isTrue();
        assertThat(svc.isWithin(base, tmp.resolve("elsewhere"))).isFalse();
    }
}
