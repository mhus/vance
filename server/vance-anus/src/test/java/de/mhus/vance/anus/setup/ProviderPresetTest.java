package de.mhus.vance.anus.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProviderPresetTest {

    @Test
    void custom_isOpenAiCompatibleGatewayRequiringBaseUrlAndAnInstanceName() {
        ProviderPreset custom = ProviderPreset.CUSTOM;

        assertThat(custom.requiresBaseUrl()).isTrue();
        assertThat(custom.requiresInstanceName()).isTrue();
        assertThat(custom.defaultModel()).isEmpty(); // no sensible default — operator sets it
        assertThat(custom.supportsEmbedding()).isFalse(); // custom chat endpoint may lack embeddings
    }

    /**
     * The regression this whole change is about: {@code CUSTOM} used to
     * report the literal {@code openai} settings id, so setting up a gateway
     * through the wizard overwrote the real OpenAI key and pointed the
     * {@code openai} instance at the gateway. Throwing is the point — a
     * caller that has not asked for an instance name must fail, not silently
     * land in a shared namespace.
     */
    @Test
    void custom_hasNoFixedSettingsId() {
        assertThatThrownBy(ProviderPreset.CUSTOM::settingsId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("settingsIdOr");
    }

    @Test
    void custom_writesUnderTheOperatorChosenInstance() {
        assertThat(ProviderPreset.CUSTOM.settingsIdOr("cortecs")).isEqualTo("cortecs");
    }

    @Test
    void custom_withoutInstanceName_throws() {
        assertThatThrownBy(() -> ProviderPreset.CUSTOM.settingsIdOr(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderPreset.CUSTOM.settingsIdOr("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fixedPresets_ignoreAnInstanceName() {
        // Passing one is not an error, it just has nothing to bind to — the
        // preset owns its namespace.
        assertThat(ProviderPreset.OPENAI.settingsIdOr("cortecs")).isEqualTo("openai");
        assertThat(ProviderPreset.OPENAI.requiresInstanceName()).isFalse();
    }

    @Test
    void onlyCustom_requiresBaseUrlAndInstanceName() {
        assertThat(Arrays.stream(ProviderPreset.values())
                        .filter(ProviderPreset::requiresBaseUrl))
                .containsExactly(ProviderPreset.CUSTOM);
        assertThat(Arrays.stream(ProviderPreset.values())
                        .filter(ProviderPreset::requiresInstanceName))
                .containsExactly(ProviderPreset.CUSTOM);
    }

    // ──── Instance-name grammar ────────────────────────────────────────

    @Test
    void instanceName_lowerCasesAndTrims() {
        // Not rejected: the name is echoed straight into a settings key, and
        // "Cortecs" quietly becoming a second namespace beside "cortecs" is
        // the exact failure mode this change removes.
        assertThat(ProviderPreset.normaliseInstanceName("  Cortecs ")).isEqualTo("cortecs");
    }

    @Test
    void instanceName_acceptsTheCatalogGrammar() {
        // Must match ModelCatalog's PROVIDER_NAME_RE — the name doubles as a
        // directory under _vance/model/.
        for (String ok : new String[]{"cortecs", "openrouter", "vllm-local", "a.b_c-1"}) {
            assertThat(ProviderPreset.normaliseInstanceName(ok)).isEqualTo(ok);
        }
    }

    @Test
    void instanceName_rejectsWhatWouldBreakAKeyOrADirectory() {
        for (String bad : new String[]{"", "   ", "with space", "slash/es", "colon:s", null}) {
            assertThat(ProviderPreset.normaliseInstanceName(bad))
                    .as("instance name '%s'", bad)
                    .isNull();
        }
    }

    // ──── Read-back ────────────────────────────────────────────────────

    @Test
    void fromSettingsId_openai_resolvesToOpenAi() {
        // CUSTOM no longer shares the id, so there is nothing to disambiguate.
        assertThat(ProviderPreset.fromSettingsId("openai")).isEqualTo(ProviderPreset.OPENAI);
    }

    @Test
    void fromSettingsId_operatorNamedInstance_returnsNull() {
        // Null is the signal the wizard reads as "this is a CUSTOM instance
        // called <id>" — see SetupWizard.prefill.
        assertThat(ProviderPreset.fromSettingsId("cortecs")).isNull();
        assertThat(ProviderPreset.fromSettingsId("does-not-exist")).isNull();
    }
}
