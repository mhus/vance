package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionWeight;
import de.mhus.vance.shared.util.JsonSchemaLight;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WonkoPromptsTest {

    private static final Map<String, Object> CHECK_REPLY = Map.of(
            "findings",
                    List.of(
                            Map.of(
                                    "key",
                                    "Retry Policy/Count",
                                    "title",
                                    "Retry count",
                                    "category",
                                    "Error Handling",
                                    "concern",
                                    "contradiction",
                                    "weight",
                                    "blocker",
                                    "question",
                                    "3 or 5?",
                                    "kind",
                                    "choice",
                                    "options",
                                    List.of("3", "5"),
                                    "dependsOn",
                                    "retry policy"),
                            Map.of("key", " ", "title", "dropped", "weight", "minor", "question", "x")),
            "categoryChanges",
                    List.of(
                            Map.of("op", "define", "category", "money", "reason", "rounding"),
                            Map.of("op", "teleport", "category", "x")));

    @Test
    void checkSchema_acceptsAWellFormedReply_rejectsUnknownOp() {
        Map<String, Object> wellFormed = Map.of(
                "findings",
                        List.of(Map.of(
                                "key",
                                "retry",
                                "title",
                                "Retry",
                                "weight",
                                "important",
                                "question",
                                "How often?",
                                "kind",
                                "text",
                                "contradictsUser",
                                false)),
                "categoryChanges", List.of(Map.of("op", "level", "category", "naming", "level", "decide")));

        assertThat(JsonSchemaLight.validate(wellFormed, WonkoPrompts.checkSchema())
                        .valid())
                .isTrue();
        assertThat(JsonSchemaLight.validate(CHECK_REPLY, WonkoPrompts.checkSchema())
                        .valid())
                .isFalse();
    }

    @Test
    void parseCheck_normalisesKeysAndEnums_dropsUnusable() {
        var result = WonkoPrompts.parseCheck(CHECK_REPLY);

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.key()).isEqualTo("retry-policy/count");
            assertThat(f.category()).isEqualTo("error-handling");
            assertThat(f.dependsOn()).isEqualTo("retry-policy");
            assertThat(f.weight()).isEqualTo(QuestionWeight.BLOCKER);
            assertThat(f.kind()).isEqualTo(QuestionKind.CHOICE);
            assertThat(f.options()).containsExactly("3", "5");
            assertThat(f.isContradiction()).isTrue();
        });
        assertThat(result.categoryChanges())
                .singleElement()
                .extracting(WonkoCategoryChange::op)
                .isEqualTo(WonkoCategoryChange.Op.DEFINE);
    }

    @Test
    void checkPrompt_carriesRegisterSettledMarkerAndSubject() {
        WonkoRegister r = WonkoTestData.register(MandateLevel.CONFIRM);
        WonkoTestData.topic(r, "retry-policy", WonkoTopicStatus.SETTLED);

        String prompt = WonkoPrompts.checkPrompt(
                "Check it",
                List.of(new WonkoPrompts.SubjectDoc("spec.md", "BODY")),
                r,
                List.of("error cases"),
                List.of("performance does not matter"),
                2,
                6);

        assertThat(prompt)
                .contains(
                        "`retry-policy` [SETTLED]",
                        "whole branch is closed",
                        "BODY",
                        "error cases",
                        "performance does not matter",
                        "2 of at most 6");
    }

    @Test
    void parseInterpret_readsAnswersStopAndUserMandate() {
        var result = WonkoPrompts.parseInterpret(Map.of(
                "answers",
                        List.of(
                                Map.of("key", "timeout", "outcome", "answered", "value", "30 s"),
                                Map.of("key", "rounding", "outcome", "nonsense")),
                "stop", true,
                "mandateChanges", List.of(Map.of("category", "naming", "level", "decide"))));

        assertThat(result.answers()).singleElement().satisfies(a -> {
            assertThat(a.getKey()).isEqualTo("timeout");
            assertThat(a.getOutcome()).isEqualTo(QuestionOutcome.ANSWERED);
        });
        assertThat(result.stop()).isTrue();
        assertThat(result.mandateChanges()).singleElement().satisfies(m -> {
            assertThat(m.level()).isEqualTo(MandateLevel.DECIDE);
            assertThat(m.locked()).isNull();
        });
    }
}
