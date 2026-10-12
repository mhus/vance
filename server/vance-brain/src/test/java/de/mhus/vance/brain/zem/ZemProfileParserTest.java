package de.mhus.vance.brain.zem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.zem.ZemQuestionType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZemProfileParserTest {

    @Test
    void parses_the_documented_recipe_shape() {
        ZemProfile profile = ZemProfileParser.parse(
                "command-safety",
                Map.of(
                        "model", "openrouter:typesafe/jev-1.13",
                        "stateSelect", List.of("command", "args.path"),
                        "questions",
                                List.of(Map.of(
                                        "name", "unsafe",
                                        "type", "noul",
                                        "instructions", "Is `command` destructive?",
                                        "criteria", Map.of(true, "destructive", false, "harmless")))));

        assertThat(profile.name()).isEqualTo("command-safety");
        assertThat(profile.model()).isEqualTo("openrouter:typesafe/jev-1.13");
        assertThat(profile.stateSelect()).containsExactly("command", "args.path");
        assertThat(profile.questions()).singleElement().satisfies(q -> {
            assertThat(q.getName()).isEqualTo("unsafe");
            assertThat(q.getType()).isEqualTo(ZemQuestionType.NOUL);
        });
    }

    @Test
    void type_is_case_insensitive_and_model_optional() {
        ZemProfile profile = ZemProfileParser.parse(
                "p", Map.of("questions", List.of(Map.of("name", "s", "type", "Score", "instructions", "?"))));

        assertThat(profile.model()).isNull();
        assertThat(profile.stateSelect()).isEmpty();
        assertThat(profile.questions().get(0).getType()).isEqualTo(ZemQuestionType.SCORE);
    }

    @Test
    void shape_errors_name_the_profile() {
        assertThatThrownBy(() -> ZemProfileParser.parse("p", Map.of("questions", "nope")))
                .isInstanceOfSatisfying(
                        ZemException.class, e -> assertThat(e.reason()).isEqualTo(ZemException.Reason.INVALID_PROFILE))
                .hasMessageContaining("'p'");
        assertThatThrownBy(() -> ZemProfileParser.parse(
                        "p",
                        Map.of("questions", List.of(Map.of("name", "q", "type", "predicate", "instructions", "?")))))
                .hasMessageContaining("unknown type 'predicate'");
        assertThatThrownBy(() ->
                        ZemProfileParser.parse("p", Map.of("questions", List.of(Map.of("name", "q", "type", "noul")))))
                .hasMessageContaining("no 'instructions'");
        assertThatThrownBy(() -> ZemProfileParser.parse("p", Map.of("stateSelect", "message")))
                .hasMessageContaining("stateSelect");
    }
}
