package de.mhus.vance.shared.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;

class EmailTokenModeTest {

    private final SettingService settings = mock(SettingService.class);

    private void tenantChoice(String tenant, boolean value) {
        when(settings.getBooleanValue(
                        tenant,
                        SettingService.SCOPE_PROJECT,
                        ProjectService.TENANT_PROJECT_NAME,
                        EmailTokenMode.SETTING_KEY,
                        false))
                .thenReturn(value);
    }

    @Test
    void aTenantThatHasNotChosenKeepsItsPassword() {
        EmailTokenMode mode = new EmailTokenMode(settings, true);

        assertThat(mode.instanceEnabled()).isTrue();
        assertThat(mode.enabledFor("apple")).isFalse();
    }

    @Test
    void aTenantThatChoseItSignsInWithTheCode() {
        tenantChoice("mhus", true);
        EmailTokenMode mode = new EmailTokenMode(settings, true);

        assertThat(mode.enabledFor("mhus")).isTrue();
        assertThat(mode.enabledFor("apple")).isFalse();
    }

    @Test
    void theTenantChoiceDoesNothingWhenTheInstanceDoesNotOfferTheCode() {
        tenantChoice("mhus", true);
        EmailTokenMode mode = new EmailTokenMode(settings, false);

        assertThat(mode.instanceEnabled()).isFalse();
        assertThat(mode.enabledFor("mhus")).isFalse();
    }
}
