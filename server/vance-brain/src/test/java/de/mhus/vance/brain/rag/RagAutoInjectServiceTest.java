package de.mhus.vance.brain.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.rag.RagAutoInjectService.Mode;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Enablement-resolution matrix for {@link RagAutoInjectService}: the
 * three-state recipe param and cascade setting, and the innermost-wins
 * precedence (recipe ON/OFF beats any setting; AUTO/absent defers).
 */
@ExtendWith(MockitoExtension.class)
class RagAutoInjectServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";

    @Mock private SettingService settingService;

    private RagAutoInjectService newService() {
        return new RagAutoInjectService(mock(ProjectRagService.class),
                mock(RagService.class), settingService);
    }

    private ThinkProcessDocument processWithParam(@Nullable Object autoInjectValue) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (autoInjectValue != null) {
            // Nested form, as recipes write it: rag: { autoInject: <v> }.
            Map<String, Object> rag = new LinkedHashMap<>();
            rag.put("autoInject", autoInjectValue);
            params.put("rag", rag);
        }
        return ThinkProcessDocument.builder()
                .tenantId(TENANT)
                .projectId(PROJECT)
                .engineParams(params)
                .build();
    }

    private void settingReturns(String key, @Nullable String value) {
        lenient().when(settingService.getStringValueCascade(TENANT, PROJECT, null, key))
                .thenReturn(value);
    }

    // --- parseMode: value vocabulary -------------------------------------

    @Test
    void parseMode_booleans_mapToOnOff() {
        assertThat(RagAutoInjectService.parseMode(Boolean.TRUE)).isEqualTo(Mode.ON);
        assertThat(RagAutoInjectService.parseMode(Boolean.FALSE)).isEqualTo(Mode.OFF);
    }

    @Test
    void parseMode_enumAndLegacyStrings_areRecognised() {
        assertThat(RagAutoInjectService.parseMode("ON")).isEqualTo(Mode.ON);
        assertThat(RagAutoInjectService.parseMode("off")).isEqualTo(Mode.OFF);
        assertThat(RagAutoInjectService.parseMode("Auto")).isEqualTo(Mode.AUTO);
        assertThat(RagAutoInjectService.parseMode("true")).isEqualTo(Mode.ON);
        assertThat(RagAutoInjectService.parseMode("false")).isEqualTo(Mode.OFF);
    }

    @Test
    void parseMode_absentOrUnknown_isNull() {
        assertThat(RagAutoInjectService.parseMode(null)).isNull();
        assertThat(RagAutoInjectService.parseMode("  ")).isNull();
        assertThat(RagAutoInjectService.parseMode("maybe")).isNull();
    }

    // --- precedence: recipe intent wins over the setting -----------------

    @Test
    void recipeOff_beatsSettingOn() {
        settingReturns(RagAutoInjectService.SETTING_AUTO_INJECT_ENABLED, "ON");
        assertThat(newService().isEnabled(processWithParam("OFF"))).isFalse();
    }

    @Test
    void recipeOn_beatsSettingOff() {
        settingReturns(RagAutoInjectService.SETTING_AUTO_INJECT_ENABLED, "OFF");
        assertThat(newService().isEnabled(processWithParam("ON"))).isTrue();
    }

    // --- precedence: AUTO/absent recipe defers to the setting ------------

    @Test
    void recipeAuto_defersToSettingOn() {
        settingReturns(RagAutoInjectService.SETTING_AUTO_INJECT_ENABLED, "ON");
        assertThat(newService().isEnabled(processWithParam("AUTO"))).isTrue();
    }

    @Test
    void recipeAbsent_defersToSettingOn() {
        settingReturns(RagAutoInjectService.SETTING_AUTO_INJECT_ENABLED, "ON");
        assertThat(newService().isEnabled(processWithParam(null))).isTrue();
    }

    @Test
    void recipeAbsent_settingOff_isFalse() {
        settingReturns(RagAutoInjectService.SETTING_AUTO_INJECT_ENABLED, "OFF");
        assertThat(newService().isEnabled(processWithParam(null))).isFalse();
    }

    // --- setting takes both enum tokens and legacy booleans --------------

    @Test
    void settingAcceptsLegacyBoolean() {
        settingReturns(RagAutoInjectService.SETTING_AUTO_INJECT_ENABLED, "true");
        assertThat(newService().isEnabled(processWithParam(null))).isTrue();
    }

    @Test
    void settingAuto_defersToDefaultOff() {
        // AUTO at the setting level = "no forced opinion at this scope" →
        // resolution falls through to the hard default OFF.
        settingReturns(RagAutoInjectService.SETTING_AUTO_INJECT_ENABLED, "AUTO");
        assertThat(newService().isEnabled(processWithParam(null))).isFalse();
    }

    // --- default floor ---------------------------------------------------

    @Test
    void nothingSet_defaultsOff() {
        assertThat(newService().isEnabled(processWithParam(null))).isFalse();
    }
}
