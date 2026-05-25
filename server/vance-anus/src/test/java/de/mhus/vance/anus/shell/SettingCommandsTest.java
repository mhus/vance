package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.anus.shell.SettingCommands.StorageRef;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;

class SettingCommandsTest {

    @Test
    void mapToStorage_tenantScope_resolvesToTenantSystemProject_andIgnoresRef() {
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_TENANT, null);

        assertThat(ref.type()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(ref.id()).isEqualTo(HomeBootstrapService.TENANT_PROJECT_NAME);
    }

    @Test
    void mapToStorage_tenantScope_ignoresProvidedRef() {
        // tenant has exactly one system project per tenant; --ref makes no sense.
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_TENANT, "ignored");

        assertThat(ref.id()).isEqualTo(HomeBootstrapService.TENANT_PROJECT_NAME);
    }

    @Test
    void mapToStorage_userScope_prefixesLoginIntoHubProject() {
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_USER, "alice");

        assertThat(ref.type()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(ref.id()).isEqualTo(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + "alice");
    }

    @Test
    void mapToStorage_userScope_blankRefIsRejected() {
        assertThatThrownBy(() -> SettingCommands.mapToStorage(SettingService.SCOPE_USER, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope=user");
    }

    @Test
    void mapToStorage_projectScope_passesRefThrough() {
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_PROJECT, "literature-review");

        assertThat(ref.type()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(ref.id()).isEqualTo("literature-review");
    }

    @Test
    void mapToStorage_projectScope_blankRefIsRejected() {
        assertThatThrownBy(() -> SettingCommands.mapToStorage(SettingService.SCOPE_PROJECT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--ref");
    }

    @Test
    void mapToStorage_thinkProcessScope_passesRefThrough() {
        StorageRef ref = SettingCommands.mapToStorage(SettingService.SCOPE_THINK_PROCESS, "tp-42");

        assertThat(ref.type()).isEqualTo(SettingService.SCOPE_THINK_PROCESS);
        assertThat(ref.id()).isEqualTo("tp-42");
    }

    @Test
    void mapToStorage_unknownScopeIsRejected() {
        assertThatThrownBy(() -> SettingCommands.mapToStorage("nonsense", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown scope");
    }

    @Test
    void storageToWire_tenantSystemProject_becomesTenantScopeWithEmptyRef() {
        StorageRef wire = SettingCommands.storageToWire(
                SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME);

        assertThat(wire.type()).isEqualTo(SettingService.SCOPE_TENANT);
        assertThat(wire.id()).isEqualTo("");
    }

    @Test
    void storageToWire_hubProject_becomesUserScopeWithLogin() {
        StorageRef wire = SettingCommands.storageToWire(
                SettingService.SCOPE_PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + "bob");

        assertThat(wire.type()).isEqualTo(SettingService.SCOPE_USER);
        assertThat(wire.id()).isEqualTo("bob");
    }

    @Test
    void storageToWire_regularProject_staysAsProject() {
        StorageRef wire = SettingCommands.storageToWire(
                SettingService.SCOPE_PROJECT, "literature-review");

        assertThat(wire.type()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(wire.id()).isEqualTo("literature-review");
    }

    @Test
    void storageToWire_thinkProcess_passesThrough() {
        StorageRef wire = SettingCommands.storageToWire(
                SettingService.SCOPE_THINK_PROCESS, "tp-7");

        assertThat(wire.type()).isEqualTo(SettingService.SCOPE_THINK_PROCESS);
        assertThat(wire.id()).isEqualTo("tp-7");
    }
}
