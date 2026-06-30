package de.mhus.vance.foot.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionConfigLoaderTest {

    private PermissionConfigLoader loader(Path central, Path local) {
        return new PermissionConfigLoader(
                central == null ? "" : central.toString(),
                local == null ? "" : local.toString());
    }

    @Test
    void load_missingFile_returnsDefaults(@TempDir Path dir) {
        PermissionConfigLoader loader = loader(dir.resolve("nope.yaml"), null);

        PermissionConfig cfg = loader.load();

        assertThat(cfg.getSandbox()).isNull();
        assertThat(cfg.getPaths().getDeny()).isEmpty();
        assertThat(cfg.getPaths().getAllow()).isEmpty();
    }

    @Test
    void load_parsesPermissionsBlock(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.yaml");
        Files.writeString(file, """
                permissions:
                  sandbox: true
                  paths:
                    deny: ["/etc/**"]
                    allow: ["~/projects/**"]
                  commands:
                    allow: ["^git( |$)"]
                """);
        PermissionConfigLoader loader = loader(file, null);

        PermissionConfig cfg = loader.load();

        assertThat(cfg.getSandbox()).isTrue();
        assertThat(cfg.getPaths().getDeny()).containsExactly("/etc/**");
        assertThat(cfg.getPaths().getAllow()).containsExactly("~/projects/**");
        assertThat(cfg.getCommands().getAllow()).containsExactly("^git( |$)");
    }

    @Test
    void appendRule_roundTrips_andIsIdempotent(@TempDir Path dir) {
        Path file = dir.resolve("permissions.yaml");
        PermissionConfigLoader loader = loader(file, null);

        loader.appendRule(PermissionDomain.PATHS, false, "/secret/**");
        loader.appendRule(PermissionDomain.PATHS, false, "/secret/**"); // duplicate

        PermissionConfig reloaded = loader.load();
        assertThat(reloaded.getPaths().getDeny()).containsExactly("/secret/**");
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void appendRule_createsParentDirectory(@TempDir Path dir) {
        Path file = dir.resolve("nested/deeper/permissions.yaml");
        PermissionConfigLoader loader = loader(file, null);

        loader.appendRule(PermissionDomain.COMMANDS, true, "^ls( |$)");

        assertThat(loader.load().getCommands().getAllow()).containsExactly("^ls( |$)");
    }

    @Test
    void loadPolicy_includesFloorDeny_evenWithMissingFile(@TempDir Path dir) {
        PermissionConfigLoader loader = loader(dir.resolve("nope.yaml"), null);

        PermissionPolicy policy = loader.loadPolicy();
        Path sshKey = Path.of(System.getProperty("user.home"), ".ssh", "id_rsa");

        assertThat(policy.evaluatePath(sshKey)).isEqualTo(PermissionDecision.DENY);
    }

    @Test
    void load_brokenFile_throws(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.yaml");
        Files.writeString(file, "permissions:\n  sandbox: [unclosed\n");
        PermissionConfigLoader loader = loader(file, null);

        assertThatThrownBy(loader::load)
                .isInstanceOf(PermissionConfigException.class)
                .hasMessageContaining(file.toString());
    }

    // --- local tightening cascade ---

    @Test
    void effectiveConfig_localDeny_isAppendedToCentralDeny(@TempDir Path dir) throws Exception {
        Path central = dir.resolve("central.yaml");
        Path local = dir.resolve("local.yaml");
        Files.writeString(central, """
                permissions:
                  paths:
                    deny: ["/etc/**"]
                    allow: ["~/projects/**"]
                """);
        Files.writeString(local, """
                permissions:
                  paths:
                    deny: ["/var/secret/**"]
                """);
        PermissionConfig eff = loader(central, local).effectiveConfig();

        assertThat(eff.getPaths().getDeny()).containsExactly("/etc/**", "/var/secret/**");
        assertThat(eff.getPaths().getAllow()).containsExactly("~/projects/**");
    }

    @Test
    void effectiveConfig_localAllow_isIgnored(@TempDir Path dir) throws Exception {
        Path central = dir.resolve("central.yaml");
        Path local = dir.resolve("local.yaml");
        Files.writeString(central, "permissions:\n  paths:\n    allow: [\"~/projects/**\"]\n");
        Files.writeString(local, "permissions:\n  paths:\n    allow: [\"/etc/**\"]\n");

        PermissionConfig eff = loader(central, local).effectiveConfig();

        assertThat(eff.getPaths().getAllow()).containsExactly("~/projects/**");
    }

    @Test
    void effectiveConfig_localSandboxTrue_forcesOn_evenIfCentralOff(@TempDir Path dir)
            throws Exception {
        Path central = dir.resolve("central.yaml");
        Path local = dir.resolve("local.yaml");
        Files.writeString(central, "permissions:\n  sandbox: false\n");
        Files.writeString(local, "permissions:\n  sandbox: true\n");

        assertThat(loader(central, local).effectiveConfig().getSandbox()).isTrue();
    }

    @Test
    void effectiveConfig_localSandboxFalse_cannotWeakenCentralOn(@TempDir Path dir)
            throws Exception {
        Path central = dir.resolve("central.yaml");
        Path local = dir.resolve("local.yaml");
        Files.writeString(central, "permissions:\n  sandbox: true\n");
        Files.writeString(local, "permissions:\n  sandbox: false\n");

        assertThat(loader(central, local).effectiveConfig().getSandbox()).isTrue();
    }

    @Test
    void effectiveConfig_centralOff_andNoLocalFile_staysOff(@TempDir Path dir) throws Exception {
        Path central = dir.resolve("central.yaml");
        Files.writeString(central, "permissions:\n  sandbox: false\n");
        // local file deliberately absent
        PermissionConfig eff = loader(central, dir.resolve("absent.yaml")).effectiveConfig();

        assertThat(eff.getSandbox()).isFalse();
    }

    // --- exec isolation merge ---

    private static String isolationYaml(String wrapper) {
        return """
                permissions:
                  exec:
                    isolation:
                      mode: custom
                      wrapper: "%s"
                """.formatted(wrapper);
    }

    @Test
    void effectiveConfig_centralIsolation_wins(@TempDir Path dir) throws Exception {
        Path central = dir.resolve("central.yaml");
        Path local = dir.resolve("local.yaml");
        Files.writeString(central, isolationYaml("central {cmd}"));
        Files.writeString(local, isolationYaml("local {cmd}"));

        PermissionConfig eff = loader(central, local).effectiveConfig();

        assertThat(eff.getExec()).isNotNull();
        assertThat(eff.getExec().getIsolation().getWrapper()).isEqualTo("central {cmd}");
    }

    @Test
    void effectiveConfig_localIsolation_introducesWhenCentralNone(@TempDir Path dir)
            throws Exception {
        Path central = dir.resolve("central.yaml");
        Path local = dir.resolve("local.yaml");
        Files.writeString(central, "permissions:\n  sandbox: true\n"); // no isolation
        Files.writeString(local, isolationYaml("local {cmd}"));

        PermissionConfig eff = loader(central, local).effectiveConfig();

        assertThat(eff.getExec()).isNotNull();
        assertThat(eff.getExec().getIsolation().getWrapper()).isEqualTo("local {cmd}");
    }

    @Test
    void effectiveConfig_localCannotDisableCentralIsolation(@TempDir Path dir) throws Exception {
        Path central = dir.resolve("central.yaml");
        Path local = dir.resolve("local.yaml");
        Files.writeString(central, isolationYaml("central {cmd}"));
        Files.writeString(local, "permissions:\n  exec:\n    isolation:\n      mode: none\n");

        PermissionConfig eff = loader(central, local).effectiveConfig();

        assertThat(eff.getExec()).isNotNull();
        assertThat(eff.getExec().getIsolation().getWrapper()).isEqualTo("central {cmd}");
    }

    @Test
    void effectiveConfig_noIsolationAnywhere_leavesExecNull(@TempDir Path dir) throws Exception {
        Path central = dir.resolve("central.yaml");
        Files.writeString(central, "permissions:\n  sandbox: true\n");

        assertThat(loader(central, null).effectiveConfig().getExec()).isNull();
    }
}
