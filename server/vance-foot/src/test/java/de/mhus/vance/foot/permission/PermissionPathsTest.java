package de.mhus.vance.foot.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PermissionPathsTest {

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
