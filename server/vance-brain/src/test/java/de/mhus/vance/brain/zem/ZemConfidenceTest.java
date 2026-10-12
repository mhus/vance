package de.mhus.vance.brain.zem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemAnswerType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The formulas must reproduce Jev's own values from the Z0 recording. */
class ZemConfidenceTest {

    @Test
    void choice_formula_reproduces_jev() {
        Map<String, Double> p = new LinkedHashMap<>();
        p.put("refund", 0.06);
        p.put("duplicate_charge", 0.94);
        p.put("other", 0.0);

        assertThat(ZemConfidence.choice(p)).isCloseTo(0.91, within(0.005));
    }

    @Test
    void score_formula_reproduces_jev() {
        assertThat(ZemConfidence.score(Map.of("0", 0.0, "1", 0.82, "2", 0.18))).isCloseTo(0.73, within(0.005));
        assertThat(ZemConfidence.score(Map.of("0", 1.0, "1", 0.0))).isEqualTo(1.0);
    }

    @Test
    void noul_gets_one_computed_the_provider_value_is_kept() {
        ZemAnswerDto noul = ZemAnswerDto.builder()
                .name("q")
                .type(ZemAnswerType.NOUL)
                .noul(0.9)
                .build();
        assertThat(ZemConfidence.fill(noul).getConfidence()).isCloseTo(0.8, within(1e-9));

        ZemAnswerDto reported = ZemAnswerDto.builder()
                .name("q")
                .type(ZemAnswerType.SCORE)
                .score(0.8)
                .probabilities(Map.of("0", 0.378, "1", 0.429, "2", 0.193))
                .confidence(0.046) // Ollama's own number — differs from the formula, stays
                .build();
        assertThat(ZemConfidence.fill(reported).getConfidence()).isEqualTo(0.046);
    }

    @Test
    void refusal_and_missing_distribution_stay_without_confidence() {
        assertThat(ZemConfidence.fill(ZemAnswerDto.builder()
                                .name("q")
                                .type(ZemAnswerType.REFUSAL)
                                .build())
                        .getConfidence())
                .isNull();
        assertThat(ZemConfidence.fill(ZemAnswerDto.builder()
                                .name("q")
                                .type(ZemAnswerType.CHOICE)
                                .choice("a")
                                .build())
                        .getConfidence())
                .isNull();
    }
}
