package de.mhus.vance.brain.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** The allowlisted cascade read the Web-UI uses for project-dependent config. */
class SettingsCascadeControllerTest {

    private SettingService settings;
    private SettingsCascadeController controller;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        controller = new SettingsCascadeController(settings);
    }

    @Test
    void resolve_aiDefaultKeys_omitsWhatTheTenantHasNotSet() {
        when(settings.getStringValueCascade("acme", null, null, "ai.default.provider"))
                .thenReturn("openrouter");

        Map<String, String> out = controller.resolve("acme", null, List.of("ai.default.provider", "ai.default.model"));

        assertThat(out).containsExactly(Map.entry("ai.default.provider", "openrouter"));
    }

    @Test
    void resolve_keyOutsideTheAllowlist_isRejected() {
        assertThatThrownBy(() -> controller.resolve("acme", null, List.of("ai.provider.openrouter.apiKey")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
