package de.mhus.vance.brain.zem;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemAnswerType;
import de.mhus.vance.api.zem.ZemDecisionDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZemDecisionJsonTest {

    private static final ZemDecisionDto DECISION = ZemDecisionDto.builder()
            .model("m")
            .answers(List.of(
                    ZemAnswerDto.builder()
                            .name("unsafe")
                            .type(ZemAnswerType.NOUL)
                            .noul(0.9975273768433653)
                            .confidence(0.995)
                            .build(),
                    ZemAnswerDto.builder()
                            .name("urgency")
                            .type(ZemAnswerType.SCORE)
                            .score(1.17)
                            .probabilities(Map.of("0", 0.0, "1", 0.29000000000000004))
                            .build(),
                    ZemAnswerDto.builder().name("x").type(ZemAnswerType.REFUSAL).build()))
            .build();

    @Test
    void keyed_by_name_rounded_to_three_places() {
        Map<String, Object> out = ZemDecisionJson.answers(DECISION);

        assertThat(out.keySet()).containsExactly("unsafe", "urgency", "x");
        assertThat(out.get("unsafe")).isEqualTo(Map.of("type", "noul", "noul", 0.998, "confidence", 0.995));
        assertThat(((Map<?, ?>) out.get("urgency")).get("p")).isEqualTo(Map.of("0", 0.0, "1", 0.29));
        assertThat(out.get("x")).isEqualTo(Map.of("type", "refusal"));
    }

    @Test
    void distribution_can_be_dropped_for_bulk_lines() {
        @SuppressWarnings("unchecked")
        Map<String, Object> urgency =
                (Map<String, Object>) ZemDecisionJson.answers(DECISION, false).get("urgency");

        assertThat(urgency).doesNotContainKey("p").containsEntry("score", 1.17);
    }
}
