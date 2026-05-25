package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DevModeCommandsTest {

    private SettingService settingService;
    private DevModeCommands commands;

    @BeforeEach
    void setUp() {
        settingService = Mockito.mock(SettingService.class);
        commands = new DevModeCommands(settingService);
    }

    @Test
    void showPassword_existingValue_returnsPlaintext() {
        when(settingService.getDecryptedPassword(
                "acme", SettingService.SCOPE_PROJECT, "lit-review", "openai.key"))
                .thenReturn("sk-secret");

        String result = commands.showPassword("acme", SettingService.SCOPE_PROJECT, "lit-review", "openai.key");

        assertThat(result).isEqualTo("sk-secret");
    }

    @Test
    void showPassword_missingSetting_returnsFriendlyMessage() {
        when(settingService.getDecryptedPassword(
                eq("acme"), eq(SettingService.SCOPE_PROJECT), eq("lit-review"), eq("missing")))
                .thenReturn(null);

        String result = commands.showPassword("acme", SettingService.SCOPE_PROJECT, "lit-review", "missing");

        assertThat(result)
                .contains("Setting not found")
                .contains("missing")
                .doesNotContain("sk-");
    }

    @Test
    void showPassword_tenantScope_resolvesToTenantSystemProject() {
        when(settingService.getDecryptedPassword(
                "acme", SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, "global.token"))
                .thenReturn("global-plain");

        String result = commands.showPassword("acme", SettingService.SCOPE_TENANT, null, "global.token");

        assertThat(result).isEqualTo("global-plain");
        verify(settingService).getDecryptedPassword(
                "acme", SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, "global.token");
    }

    @Test
    void showPassword_userScope_resolvesToHubProject() {
        when(settingService.getDecryptedPassword(
                "acme", SettingService.SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + "alice", "personal.key"))
                .thenReturn("alice-plain");

        String result = commands.showPassword("acme", SettingService.SCOPE_USER, "alice", "personal.key");

        assertThat(result).isEqualTo("alice-plain");
    }

    @Test
    void showPassword_invalidScope_returnsErrorWithoutTouchingService() {
        String result = commands.showPassword("acme", "nonsense", "x", "k");

        assertThat(result).contains("Unknown scope");
        verifyNoInteractions(settingService);
    }

    @Test
    void showPassword_userScopeWithoutRef_returnsErrorWithoutTouchingService() {
        String result = commands.showPassword("acme", SettingService.SCOPE_USER, null, "k");

        assertThat(result).contains("scope=user");
        verifyNoInteractions(settingService);
    }
}
