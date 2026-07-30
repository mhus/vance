package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VancePathsTest {

    private VancePaths paths(String cwd, String home, String vanceHomeEnv) {
        return new VancePaths(null, null, vanceHomeEnv, cwd, home);
    }

    @Test
    void activeDir_prefersProjectLocalWhenPresent(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Files.createDirectories(cwd.resolve(".vancetope"));
        VancePaths paths = paths(cwd.toString(), home.toString(), null);

        assertThat(paths.activeDir()).isEqualTo(cwd.resolve(".vancetope"));
        assertThat(paths.isActiveLocal()).isTrue();
    }

    @Test
    void activeDir_fallsBackToGlobalHomeWhenNoProjectLocal(@TempDir Path cwd, @TempDir Path home) {
        VancePaths paths = paths(cwd.toString(), home.toString(), null);

        assertThat(paths.activeDir()).isEqualTo(home.resolve(".vancetope"));
        assertThat(paths.isActiveLocal()).isFalse();
    }

    @Test
    void activeDir_vanceHomeEnvOverridesUserHome(@TempDir Path cwd, @TempDir Path home, @TempDir Path env) {
        VancePaths paths = paths(cwd.toString(), home.toString(), env.toString());

        assertThat(paths.globalHomeDir()).isEqualTo(env);
        assertThat(paths.activeDir()).isEqualTo(env);
    }

    @Test
    void activeDir_ignoresProjectLocalWhenLocalDisabled(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Files.createDirectories(cwd.resolve(".vancetope"));
        VancePaths paths = paths(cwd.toString(), home.toString(), null);

        paths.setLocalEnabled(false);

        assertThat(paths.activeDir()).isEqualTo(home.resolve(".vancetope"));
        assertThat(paths.loginTargetDir()).isEqualTo(home.resolve(".vancetope"));
    }

    @Test
    void loginTargetDir_isProjectLocalByDefault(@TempDir Path cwd, @TempDir Path home) {
        VancePaths paths = paths(cwd.toString(), home.toString(), null);

        // Even when ./.vancetope does not exist yet, a fresh login targets it.
        assertThat(paths.loginTargetDir()).isEqualTo(cwd.resolve(".vancetope"));
    }
}
