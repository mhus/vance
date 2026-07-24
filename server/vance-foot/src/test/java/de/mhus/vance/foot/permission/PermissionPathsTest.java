package de.mhus.vance.foot.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionPathsTest {

    @Test
    void canonicalize_collapsesSymlinkedParent_forNotYetExistingTarget(@TempDir Path dir)
            throws Exception {
        // Security regression (code-review-2 S3): a symlinked *parent* directory
        // must be collapsed even when the target file doesn't exist yet, so a
        // write via ~/link/x can't slip past a deny-floor on the real directory
        // and then follow the symlink at the OS level.
        Path real = Files.createDirectories(dir.resolve("realdir"));
        Path link = dir.resolve("link");
        Files.createSymbolicLink(link, real);

        Path canon = PermissionPaths.canonicalize(link.resolve("newfile").toString());

        assertThat(canon).isEqualTo(real.toRealPath().resolve("newfile"));
        assertThat(canon.toString()).doesNotContain("link");
    }

    @Test
    void canonicalize_collapsesDotDot_closingTheBypass() {
        String home = System.getProperty("user.home");

        Path bypass = PermissionPaths.canonicalize("~/foo/../.ssh/id_rsa");
        Path direct = PermissionPaths.canonicalize("~/.ssh/id_rsa");

        assertThat(bypass).isEqualTo(direct);
        assertThat(bypass.toString()).doesNotContain("..");
        assertThat(bypass.toString()).startsWith(home);
    }

    @Test
    void canonicalize_relativePath_resolvesAgainstCwd() {
        String cwd = System.getProperty("user.dir");

        Path resolved = PermissionPaths.canonicalize("sub/file.txt");

        assertThat(resolved.isAbsolute()).isTrue();
        assertThat(resolved.toString()).startsWith(cwd);
        assertThat(resolved.toString()).endsWith("file.txt");
    }

    @Test
    void expandPattern_home() {
        String home = System.getProperty("user.home");
        assertThat(PermissionPaths.expandPattern("~/.ssh/**")).isEqualTo(home + "/.ssh/**");
        assertThat(PermissionPaths.expandPattern("~")).isEqualTo(home);
    }

    @Test
    void expandPattern_absoluteIsKept() {
        assertThat(PermissionPaths.expandPattern("/etc/**")).isEqualTo("/etc/**");
    }

    @Test
    void expandPattern_relativeResolvesAgainstCwd() {
        String cwd = System.getProperty("user.dir");
        assertThat(PermissionPaths.expandPattern("./**")).isEqualTo(cwd + "/**");
        assertThat(PermissionPaths.expandPattern("build/**")).isEqualTo(cwd + "/build/**");
    }
}
