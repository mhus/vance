package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineChatFactoryThinkingParamTest {

    @Test
    void noEngineParams_returnsOff() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setEngineParams(null);

        assertThat(EngineChatFactory.readThinkingLevel(process))
                .isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    void emptyEngineParams_returnsOff() {
        ThinkProcessDocument process = newProcess(Map.of());

        assertThat(EngineChatFactory.readThinkingLevel(process))
                .isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    void thinkingHigh_returnsHigh() {
        ThinkProcessDocument process = newProcess(Map.of("thinking", "high"));

        assertThat(EngineChatFactory.readThinkingLevel(process))
                .isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    void thinkingMixedCase_isNormalised() {
        ThinkProcessDocument process = newProcess(Map.of("thinking", "Medium"));

        assertThat(EngineChatFactory.readThinkingLevel(process))
                .isEqualTo(ThinkingLevel.MEDIUM);
    }

    @Test
    void thinkingBooleanTrue_mapsToMedium() {
        ThinkProcessDocument process = newProcess(Map.of("thinking", true));

        assertThat(EngineChatFactory.readThinkingLevel(process))
                .isEqualTo(ThinkingLevel.MEDIUM);
    }

    @Test
    void thinkingBooleanFalse_mapsToOff() {
        ThinkProcessDocument process = newProcess(Map.of("thinking", false));

        assertThat(EngineChatFactory.readThinkingLevel(process))
                .isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    void unknownValue_returnsOff() {
        ThinkProcessDocument process = newProcess(Map.of("thinking", "extreme"));

        assertThat(EngineChatFactory.readThinkingLevel(process))
                .isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    void unsupportedType_returnsOff() {
        ThinkProcessDocument process = newProcess(Map.of("thinking", 42));

        assertThat(EngineChatFactory.readThinkingLevel(process))
                .isEqualTo(ThinkingLevel.OFF);
    }

    private static ThinkProcessDocument newProcess(Map<String, Object> params) {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setEngineParams(new HashMap<>(params));
        return process;
    }
}
