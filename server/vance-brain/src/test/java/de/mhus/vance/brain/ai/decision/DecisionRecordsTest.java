package de.mhus.vance.brain.ai.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Invariants of the value records every provider relies on. */
class DecisionRecordsTest {

    private static ZemQuestionDto noul(String name) {
        return ZemQuestionDto.builder()
                .name(name)
                .type(ZemQuestionType.NOUL)
                .instructions("?")
                .build();
    }

    @Test
    void request_accepts_the_three_state_shapes() {
        assertThat(new DecisionRequest("text", List.of(noul("a"))).state()).isEqualTo("text");
        assertThat(new DecisionRequest(Map.of("k", 1), List.of(noul("a"))).state())
                .isEqualTo(Map.of("k", 1));
        assertThat(new DecisionRequest(List.of("x"), List.of(noul("a"))).state())
                .isEqualTo(List.of("x"));
    }

    @Test
    void request_rejects_what_every_wire_rejects() {
        assertThatThrownBy(() -> new DecisionRequest(null, List.of(noul("a"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionRequest(42, List.of(noul("a"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionRequest("x", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionRequest("x", List.of(noul("a"), noul("a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void model_info_defaults_are_the_minimal_profile() {
        DecisionModelInfo info = new DecisionModelInfo("p", "m", 0, 0, 0, 0, false, null, null, 0.0, 0);

        assertThat(info.timeoutSeconds()).isEqualTo(DecisionModelInfo.DEFAULT_TIMEOUT_SECONDS);
        assertThat(info.maxQuestions()).isEqualTo(64);
        assertThat(info.maxChoiceOptions()).isEqualTo(26);
        assertThat(info.maxScoreLevels()).isEqualTo(10);
        assertThat(info.rateLimitRps()).isNull();
        assertThat(info.maxConcurrentCalls()).isNull();
        assertThat(info.costForInputTokens(1000)).isNull();
    }

    @Test
    void model_info_never_exceeds_the_system_one_score_bound() {
        DecisionModelInfo info = new DecisionModelInfo("p", "m", 10, 64, 255, 26, true, null, 0.042, null, null);

        assertThat(info.maxScoreLevels()).isEqualTo(10);
        assertThat(info.costForInputTokens(1_000_000)).isEqualTo(0.042);
    }

    @Test
    void config_normalises_blank_key_and_base_url() {
        AiDecisionConfig config = new AiDecisionConfig("ollama", "ollama", "nimble", " ", "", 30);

        assertThat(config.apiKey()).isNull();
        assertThat(config.baseUrl()).isNull();
        assertThat(config.fullName()).isEqualTo("ollama:nimble");
    }
}
