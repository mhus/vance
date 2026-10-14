package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionWeight;
import org.junit.jupiter.api.Test;

class WonkoReportRendererTest {

    @Test
    void render_groupsTopicsByOutcomeAndShowsTheMandate() {
        WonkoRegister r = WonkoTestData.register(MandateLevel.CONFIRM);
        r.setRounds(3);
        WonkoTestData.topic(r, "retry", WonkoTopicStatus.ANSWERED).setAnswer("3 attempts");
        WonkoTestData.topic(r, "rounding", WonkoTopicStatus.SETTLED);
        WonkoTopic decided = WonkoTestData.topic(r, "field", WonkoTopicStatus.DECIDED);
        decided.setDecision("amountMinor");
        decided.setDecisionReason("module convention");
        WonkoTestData.topic(r, "currency", WonkoTopicStatus.LATER);
        WonkoTestData.topic(r, "typo", WonkoTopicStatus.OPEN).setWeight(QuestionWeight.MINOR);
        r.getMandate()
                .getCategories()
                .put(
                        "naming",
                        WonkoCategory.builder()
                                .level(MandateLevel.DECIDE)
                                .createdByWonko(true)
                                .build());

        String report = WonkoReportRenderer.render(r, "Nothing left worth asking.");

        assertThat(report)
                .contains("# Review: spec.md", "2 clarified · 1 decided · 1 open")
                .contains("## Clarified", "3 attempts", "good as it is")
                .contains("## Decided by me", "amountMinor", "module convention")
                .contains("## Open", "later")
                .contains("## Minor notes", "Title typo")
                .contains("| naming (new) | decide |");
    }
}
