package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Recipe-param threading for sampling-control options
 * (temperature, maxTokens, topP, topK, stopSequences, seed,
 * frequencyPenalty, presencePenalty).
 *
 * <p>Tests target {@link EngineChatFactory#applySamplingParams} directly
 * — the Spring bean isn't needed because the reader is pure.
 */
class EngineChatFactorySamplingParamsTest {

    @Test
    void nullParams_noChange() {
        AiChatOptions options = AiChatOptions.builder().build();
        ThinkProcessDocument process = newProcess(null);

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getTopP()).isNull();
        assertThat(options.getTopK()).isNull();
        assertThat(options.getSeed()).isNull();
        assertThat(options.getStopSequences()).isNull();
    }

    @Test
    void allParamsRead_typed() {
        AiChatOptions options = AiChatOptions.builder().build();
        ThinkProcessDocument process = newProcess(Map.of(
                "temperature", 0.2,
                "maxTokens", 8192,
                "topP", 0.9,
                "topK", 40,
                "stopSequences", List.of("STOP", "</end>"),
                "seed", 42L,
                "frequencyPenalty", 0.5,
                "presencePenalty", 0.3));

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getTemperature()).isEqualTo(0.2);
        assertThat(options.getMaxTokens()).isEqualTo(8192);
        assertThat(options.getTopP()).isEqualTo(0.9);
        assertThat(options.getTopK()).isEqualTo(40);
        assertThat(options.getStopSequences()).containsExactly("STOP", "</end>");
        assertThat(options.getSeed()).isEqualTo(42L);
        assertThat(options.getFrequencyPenalty()).isEqualTo(0.5);
        assertThat(options.getPresencePenalty()).isEqualTo(0.3);
    }

    @Test
    void temperatureFromInteger_widensToDouble() {
        AiChatOptions options = AiChatOptions.builder().build();
        ThinkProcessDocument process = newProcess(Map.of("temperature", 1));

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getTemperature()).isEqualTo(1.0);
    }

    @Test
    void seedFromInteger_widensToLong() {
        AiChatOptions options = AiChatOptions.builder().build();
        ThinkProcessDocument process = newProcess(Map.of("seed", 7));

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getSeed()).isEqualTo(7L);
    }

    @Test
    void temperatureFromString_parses() {
        AiChatOptions options = AiChatOptions.builder().build();
        ThinkProcessDocument process = newProcess(Map.of("temperature", "0.4"));

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getTemperature()).isEqualTo(0.4);
    }

    @Test
    void invalidNumeric_silentlyDropped() {
        AiChatOptions options = AiChatOptions.builder().build();
        ThinkProcessDocument process = newProcess(Map.of(
                "topP", "not a number",
                "topK", "also bad"));

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getTopP()).isNull();
        assertThat(options.getTopK()).isNull();
    }

    @Test
    void callerSetTopP_wins() {
        AiChatOptions options = AiChatOptions.builder().topP(0.1).build();
        ThinkProcessDocument process = newProcess(Map.of("topP", 0.9));

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getTopP()).isEqualTo(0.1);
    }

    @Test
    void recipeTemperature_winsOverDefault() {
        // temperature has a non-null default (0.7) so we can't detect
        // caller-explicit override; recipe always wins when set.
        AiChatOptions options = AiChatOptions.builder().build();
        assertThat(options.getTemperature()).isEqualTo(0.7);
        ThinkProcessDocument process = newProcess(Map.of("temperature", 0.0));

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getTemperature()).isEqualTo(0.0);
    }

    @Test
    void stopSequences_filterEmptyEntries() {
        AiChatOptions options = AiChatOptions.builder().build();
        Map<String, Object> params = new HashMap<>();
        params.put("stopSequences", List.of("a", "", "b"));
        ThinkProcessDocument process = newProcess(params);

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getStopSequences()).containsExactly("a", "b");
    }

    @Test
    void stopSequencesSingleString_wrapsToList() {
        AiChatOptions options = AiChatOptions.builder().build();
        ThinkProcessDocument process = newProcess(Map.of("stopSequences", "ENDE"));

        EngineChatFactory.applySamplingParams(options, process);

        assertThat(options.getStopSequences()).containsExactly("ENDE");
    }

    private static ThinkProcessDocument newProcess(Map<String, Object> params) {
        ThinkProcessDocument process = new ThinkProcessDocument();
        if (params != null) {
            process.setEngineParams(new HashMap<>(params));
        }
        return process;
    }
}
