package de.mhus.vance.toolpack.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The allow-list contract: a sealed environment contains the non-secret base
 * and explicitly granted names — and nothing else from the parent. This is the
 * mechanism behind GitHub issue {@code mhus/vance#62} (agent reads the Mongo
 * password from the exec environment), so the negative assertion is the point
 * of the suite.
 */
class ExecEnvPolicyTest {

    @Test
    void seal_copiesOnlyTheInheritableBase() {
        Map<String, String> parent = Map.of(
                "PATH", "/usr/bin",
                "HOME", "/home/agent",
                "MONGODB_PASSWORD", "hunter2",
                "VANCE_ENCRYPTION_PASSWORD", "hunter3",
                "GH_TOKEN", "ghp_secret");

        Map<String, String> sealed = ExecEnvPolicy.seal(parent, List.of());

        assertThat(sealed).containsExactlyInAnyOrderEntriesOf(Map.of("PATH", "/usr/bin", "HOME", "/home/agent"));
    }

    @Test
    void seal_keepsTheExecContextMarker() {
        // The manuals read the marker *inside* the child to pick the install
        // pattern (manual self-installed-tools) — stripping it would make
        // every container look like a workstation.
        Map<String, String> parent = Map.of("PATH", "/usr/bin", "VANCE_EXEC_ENV", "container");

        Map<String, String> sealed = ExecEnvPolicy.seal(parent, List.of());

        assertThat(sealed).containsEntry("VANCE_EXEC_ENV", "container");
    }

    @Test
    void seal_grantedNamesArePassedThrough() {
        Map<String, String> parent = Map.of("PATH", "/usr/bin", "GH_TOKEN", "ghp_secret");

        Map<String, String> sealed = ExecEnvPolicy.seal(parent, List.of("GH_TOKEN"));

        assertThat(sealed).containsEntry("GH_TOKEN", "ghp_secret").containsEntry("PATH", "/usr/bin");
    }

    @Test
    void seal_grantedNameMissingInParentIsAbsentNotInvented() {
        Map<String, String> sealed = ExecEnvPolicy.seal(Map.of("PATH", "/usr/bin"), List.of("NOT_SET_ANYWHERE"));

        assertThat(sealed).containsOnlyKeys("PATH");
    }

    @Test
    void seal_resultIsMutableForLayering() {
        Map<String, String> sealed = ExecEnvPolicy.seal(Map.of("PATH", "/usr/bin"), List.of());

        sealed.put("HOME", "/homes/acme/p-1");

        assertThat(sealed).containsEntry("HOME", "/homes/acme/p-1");
    }
}
