package de.mhus.vance.brain.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.access.WebUiCookies;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.settings.SettingService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UiSettingsServiceTest {

    private final SettingService settingService = mock(SettingService.class);
    private final UiSettingsService service = new UiSettingsService(settingService);

    @Test
    void load_mergesTheWebUiPrefixWithTheSetExtras() {
        when(settingService.findUserSettingsByPrefix("acme", "alice", WebUiCookies.SETTINGS_PREFIX))
                .thenReturn(Map.of("webui.theme", "dark"));
        when(settingService.getUserStringValue("acme", "alice", LanguageResolver.Keys.CHAT_LANGUAGE))
                .thenReturn("de");

        Map<String, String> settings = service.load("acme", "alice");

        assertThat(settings)
                .containsEntry("webui.theme", "dark")
                .containsEntry(LanguageResolver.Keys.CHAT_LANGUAGE, "de")
                .hasSize(2);
    }
}
