package de.mhus.vance.anus.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code effectiveInstance()} is the single place that decides which
 * {@code ai.provider.<instance>.*} namespace the wizard writes to. Every
 * setting the save path emits — key, base URL, {@code ai.default.provider},
 * the five aliases — hangs off its answer, so getting it wrong is not one
 * bad setting but a whole tenant's AI config landing on the wrong instance.
 */
class SetupStateInstanceTest {

    @Test
    void noProvider_hasNoInstance() {
        assertThat(new SetupState().effectiveInstance()).isNull();
    }

    @Test
    void fixedPreset_usesItsOwnId() {
        SetupState state = new SetupState();
        state.setProvider(ProviderPreset.OPENAI);

        assertThat(state.effectiveInstance()).isEqualTo("openai");
    }

    @Test
    void fixedPreset_ignoresAStrayInstanceName() {
        // A leftover name from a previously picked CUSTOM must not redirect a
        // fixed preset — the preset owns its namespace.
        SetupState state = new SetupState();
        state.setProvider(ProviderPreset.ANTHROPIC);
        state.setInstanceName("cortecs");

        assertThat(state.effectiveInstance()).isEqualTo("anthropic");
    }

    @Test
    void customPreset_usesTheOperatorChosenName() {
        SetupState state = new SetupState();
        state.setProvider(ProviderPreset.CUSTOM);
        state.setInstanceName("cortecs");

        assertThat(state.effectiveInstance()).isEqualTo("cortecs");
    }

    /**
     * Null rather than a fallback: the save path turns this into a refusal to
     * write. A default here would re-introduce the shared namespace through
     * the back door.
     */
    @Test
    void customPreset_withoutName_hasNoInstance() {
        SetupState state = new SetupState();
        state.setProvider(ProviderPreset.CUSTOM);

        assertThat(state.effectiveInstance()).isNull();

        state.setInstanceName("not a legal name");
        assertThat(state.effectiveInstance()).isNull();
    }

    @Test
    void customPreset_normalisesTheName() {
        SetupState state = new SetupState();
        state.setProvider(ProviderPreset.CUSTOM);
        state.setInstanceName(" Cortecs ");

        assertThat(state.effectiveInstance()).isEqualTo("cortecs");
    }
}
