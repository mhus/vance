package de.mhus.vance.brain.zem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import de.mhus.vance.brain.ai.decision.DecisionModelInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Limits from the Z0 measurement: Jev takes 255 options and structured
 * criteria, nimble 26 options and strings only. A profile written for Jev
 * must fail on nimble here, with a message naming the limit — not later
 * as a provider 400.
 */
class ZemQuestionValidatorTest {

    private static final DecisionModelInfo JEV =
            new DecisionModelInfo("openrouter", "typesafe/jev-1.13", 10, 64, 255, 10, true, 32000, 0.042, 80.0, null);
    private static final DecisionModelInfo NIMBLE =
            new DecisionModelInfo("ollama", "nimble", 30, 64, 26, 10, false, null, 0.0, null, 1);

    private static ZemQuestionDto q(String name, ZemQuestionType type, Object criteria) {
        return ZemQuestionDto.builder()
                .name(name)
                .type(type)
                .instructions("?")
                .criteria(criteria)
                .build();
    }

    private static Map<String, Object> options(int n) {
        Map<String, Object> out = new LinkedHashMap<>();
        IntStream.range(0, n).forEach(i -> out.put("o" + i, "option " + i));
        return out;
    }

    @Test
    void noul_criteria_keys_are_normalised_from_yaml_booleans() {
        Map<Object, Object> yamlStyle = new LinkedHashMap<>();
        yamlStyle.put(Boolean.FALSE, "no");
        yamlStyle.put(Boolean.TRUE, "yes");

        ZemQuestionDto out = ZemQuestionValidator.validate(List.of(q("q", ZemQuestionType.NOUL, yamlStyle)), NIMBLE)
                .get(0);

        assertThat(out.getCriteria()).isEqualTo(Map.of("true", "yes", "false", "no"));
        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) out.getCriteria()).keySet()))
                .containsExactly("true", "false");
    }

    @Test
    void noul_without_criteria_is_fine_but_one_side_is_not() {
        assertThat(ZemQuestionValidator.validate(List.of(q("q", ZemQuestionType.NOUL, null)), JEV)
                        .get(0)
                        .getCriteria())
                .isNull();
        assertThatThrownBy(() -> ZemQuestionValidator.validate(
                        List.of(q("q", ZemQuestionType.NOUL, Map.of("true", "yes"))), NIMBLE))
                .isInstanceOfSatisfying(
                        ZemException.class, e -> assertThat(e.reason()).isEqualTo(ZemException.Reason.INVALID_QUESTION))
                .hasMessageContaining("both 'true' and 'false'");
    }

    @Test
    void option_limit_comes_from_the_model() {
        List<ZemQuestionDto> thirty = List.of(q("intent", ZemQuestionType.CHOICE, options(30)));

        assertThat(ZemQuestionValidator.validate(thirty, JEV)).hasSize(1);
        assertThatThrownBy(() -> ZemQuestionValidator.validate(thirty, NIMBLE))
                .hasMessageContaining("30 options")
                .hasMessageContaining("ollama:nimble takes 2–26");
        assertThatThrownBy(() ->
                        ZemQuestionValidator.validate(List.of(q("intent", ZemQuestionType.CHOICE, options(1))), JEV))
                .hasMessageContaining("1 options");
    }

    @Test
    void choice_keeps_option_order_and_allows_null_descriptions() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("refund", null);
        criteria.put("other", "anything else");

        Object out = ZemQuestionValidator.validate(List.of(q("c", ZemQuestionType.CHOICE, criteria)), NIMBLE)
                .get(0)
                .getCriteria();

        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) out).keySet())).containsExactly("refund", "other");
    }

    @Test
    void option_keys_must_be_mongo_safe() {
        assertThatThrownBy(() -> ZemQuestionValidator.validate(
                        List.of(q("v", ZemQuestionType.CHOICE, Map.of("v1.2", "old", "v2", "new"))), JEV))
                .hasMessageContaining("'v1.2' must not contain '.'");
        assertThatThrownBy(() -> ZemQuestionValidator.validate(
                        List.of(q("v", ZemQuestionType.CHOICE, Map.of("$set", "x", "b", "y"))), JEV))
                .hasMessageContaining("'$set'");
    }

    @Test
    void structured_criteria_only_where_the_model_takes_them() {
        List<ZemQuestionDto> structured =
                List.of(q("urgency", ZemQuestionType.SCORE, List.of(Map.of("label", "low"), Map.of("label", "high"))));

        assertThat(ZemQuestionValidator.validate(structured, JEV)).hasSize(1);
        assertThatThrownBy(() -> ZemQuestionValidator.validate(structured, NIMBLE))
                .hasMessageContaining("structuredCriteria: false");
    }

    @Test
    void structured_instructions_are_accepted_everywhere() {
        ZemQuestionDto structured = ZemQuestionDto.builder()
                .name("q")
                .type(ZemQuestionType.NOUL)
                .instructions(Map.of("question", "Is it billing?", "hint", "charges count"))
                .build();

        assertThat(ZemQuestionValidator.validate(List.of(structured), NIMBLE)).hasSize(1);
    }

    @Test
    void score_levels_between_two_and_the_model_limit() {
        assertThatThrownBy(() ->
                        ZemQuestionValidator.validate(List.of(q("s", ZemQuestionType.SCORE, List.of("only"))), JEV))
                .hasMessageContaining("1 levels");
        List<String> eleven = IntStream.range(0, 11).mapToObj(i -> "l" + i).toList();
        assertThatThrownBy(() -> ZemQuestionValidator.validate(List.of(q("s", ZemQuestionType.SCORE, eleven)), JEV))
                .hasMessageContaining("11 levels");
    }

    @Test
    void names_must_be_mongo_safe_and_unique() {
        assertThatThrownBy(() -> ZemQuestionValidator.validate(List.of(q("a.b", ZemQuestionType.NOUL, null)), JEV))
                .hasMessageContaining("must match");
        assertThatThrownBy(() -> ZemQuestionValidator.validate(
                        List.of(q("a", ZemQuestionType.NOUL, null), q("a", ZemQuestionType.NOUL, null)), JEV))
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> ZemQuestionValidator.validate(List.of(), JEV)).hasMessageContaining("at least one");
    }

    @Test
    void blank_instructions_are_rejected() {
        ZemQuestionDto blank = ZemQuestionDto.builder()
                .name("q")
                .type(ZemQuestionType.NOUL)
                .instructions(" ")
                .build();

        assertThatThrownBy(() -> ZemQuestionValidator.validate(List.of(blank), JEV))
                .hasMessageContaining("instructions is blank");
    }

    @Test
    void inline_questions_replace_by_name_and_append_the_rest() {
        List<ZemQuestionDto> profile = List.of(q("a", ZemQuestionType.NOUL, null), q("b", ZemQuestionType.NOUL, null));
        ZemQuestionDto replacement = q("b", ZemQuestionType.SCORE, List.of("lo", "hi"));

        List<ZemQuestionDto> merged =
                ZemQuestionValidator.merge(profile, List.of(replacement, q("c", ZemQuestionType.NOUL, null)));

        assertThat(merged).extracting(ZemQuestionDto::getName).containsExactly("a", "b", "c");
        assertThat(merged.get(1).getType()).isEqualTo(ZemQuestionType.SCORE);
    }
}
