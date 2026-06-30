package de.mhus.vance.foot.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class PermissionServiceTest {

    private static final String WORKDIR = "/tmp/vance-iso-test";

    private PermissionService service(Path dir, String yaml) throws Exception {
        Path central = dir.resolve("permissions.yaml");
        Files.writeString(central, yaml);
        return new PermissionService(new PermissionConfigLoader(central.toString(), ""));
    }

    private String customIsolation(boolean sandbox, String wrapper) {
        return """
                permissions:
                  sandbox: %s
                  exec:
                    isolation:
                      mode: custom
                      workdir: "%s"
                      wrapper: "%s"
                """.formatted(sandbox, WORKDIR, wrapper);
    }

    @Test
    void isolation_active_whenSandboxOn_andValidWrapper(@TempDir Path dir) throws Exception {
        PermissionService s = service(dir,
                customIsolation(true, "bwrap --bind {workdir} {workdir} sh -c {cmd}"));

        ExecIsolation iso = s.isolation();
        assertThat(iso.enabled()).isTrue();
        assertThat(iso.workdir()).isEqualTo(PermissionPaths.canonicalize(WORKDIR).toString());
        assertThat(iso.wrapper()).contains("{cmd}");
    }

    @Test
    void isolation_disabled_whenSandboxOff(@TempDir Path dir) throws Exception {
        PermissionService s = service(dir,
                customIsolation(false, "bwrap sh -c {cmd}"));

        assertThat(s.isolation().enabled()).isFalse();
    }

    @Test
    void isolation_disabled_afterNoSandboxFlag(@TempDir Path dir) throws Exception {
        PermissionService s = service(dir,
                customIsolation(true, "bwrap sh -c {cmd}"));
        assertThat(s.isolation().enabled()).isTrue();

        s.disableSandbox();
        s.reload();

        assertThat(s.isolation().enabled()).isFalse();
        assertThat(s.isSandboxEnabled()).isFalse();
    }

    @Test
    void isolation_disabled_whenWrapperMissingCmdPlaceholder(@TempDir Path dir) throws Exception {
        PermissionService s = service(dir,
                customIsolation(true, "bwrap sh -c echo")); // no {cmd}

        assertThat(s.isolation().enabled()).isFalse();
    }

    @Test
    void isolation_disabled_whenModeNone(@TempDir Path dir) throws Exception {
        PermissionService s = service(dir, "permissions:\n  sandbox: true\n");

        assertThat(s.isolation().enabled()).isFalse();
    }
}
