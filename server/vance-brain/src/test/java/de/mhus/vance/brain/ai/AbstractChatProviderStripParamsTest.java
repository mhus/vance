package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link AbstractChatProvider#stripUnsupportedParams} — the gate that
 * keeps a model's refused sampling knobs off the wire. Without it a
 * reasoning model answers every turn with HTTP 400, because
 * {@link AiChatOptions} carries a temperature default.
 */
class AbstractChatProviderStripParamsTest {

    @Test
    void clearsOnlyTheDeclaredParams_leavesTheRestUntouched() {
        AiChatOptions options = AiChatOptions.builder()
                .temperature(0.7)
                .topP(0.9)
                .seed(42L)
                .maxTokens(512)
                .stopSequences(List.of("HALT"))
                .build();

        AiChatOptions stripped = AbstractChatProvider.stripUnsupportedParams(
                options, model(Set.of(SamplingParam.TEMPERATURE, SamplingParam.STOP_SEQUENCES)));

        assertThat(stripped.getTemperature()).isNull();
        assertThat(stripped.getStopSequences()).isNull();
        assertThat(stripped.getTopP()).isEqualTo(0.9);
        assertThat(stripped.getSeed()).isEqualTo(42L);
        assertThat(stripped.getMaxTokens()).isEqualTo(512);
    }

    @Test
    void returnsTheSameInstance_whenTheModelAcceptsEverything() {
        AiChatOptions options = AiChatOptions.builder().temperature(0.7).build();

        assertThat(AbstractChatProvider.stripUnsupportedParams(options, model(Set.of())))
                .isSameAs(options);
    }

    @Test
    void clearsTheTemperatureDefault_evenWhenNoCallerSetIt() {
        // The whole point: nobody asked for a temperature, the builder
        // did — and that alone is enough to break the model.
        AiChatOptions options = AiChatOptions.defaults();
        assertThat(options.getTemperature()).isNotNull();

        AiChatOptions stripped = AbstractChatProvider.stripUnsupportedParams(
                options, model(Set.of(SamplingParam.TEMPERATURE)));

        assertThat(stripped.getTemperature()).isNull();
    }

    private static ModelInfo model(Set<SamplingParam> unsupported) {
        return new ModelInfo("openai", "test-model", 128_000, 4096, ModelSize.LARGE,
                Set.of(),
                ModelInfo.DEFAULT_TIMEOUT_SECONDS,
                ModelInfo.DEFAULT_ACTION_LOOP_CORRECTIONS,
                false,
                /*messageParser*/ null,
                /*pricing*/ null,
                OutputTokenParam.MAX_TOKENS,
                unsupported,
                /*reasoningEffortWhenOff*/ null);
    }
}
