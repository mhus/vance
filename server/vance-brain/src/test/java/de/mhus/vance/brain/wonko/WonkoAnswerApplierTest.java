package de.mhus.vance.brain.wonko;

import static de.mhus.vance.brain.wonko.WonkoTestData.register;
import static de.mhus.vance.brain.wonko.WonkoTestData.topic;
import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionSetDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WonkoAnswerApplierTest {

    private static QuestionDto question(String key, QuestionKind kind, String proposal) {
        return QuestionDto.builder()
                .key(key)
                .text("?")
                .kind(kind)
                .proposal(proposal)
                .build();
    }

    @Test
    void outcomesLandOnTopics_missingAnswerCountsAsLater() {
        WonkoRegister r = register(MandateLevel.ASK);
        topic(r, "a", WonkoTopicStatus.ASKED);
        topic(r, "b", WonkoTopicStatus.ASKED);
        topic(r, "c", WonkoTopicStatus.ASKED);
        QuestionSetDto set = QuestionSetDto.builder()
                .id("s")
                .round(1)
                .questions(new ArrayList<>(List.of(
                        question("a", QuestionKind.TEXT, null),
                        question("b", QuestionKind.TEXT, null),
                        question("c", QuestionKind.TEXT, null))))
                .build();

        var stat = WonkoAnswerApplier.apply(
                r,
                set,
                List.of(
                        QuestionAnswerDto.builder()
                                .key("a")
                                .outcome(QuestionOutcome.ANSWERED)
                                .value("42")
                                .build(),
                        QuestionAnswerDto.builder()
                                .key("b")
                                .outcome(QuestionOutcome.SETTLED)
                                .build()),
                List.of(),
                1);

        assertThat(r.topic("a").orElseThrow().getAnswer()).isEqualTo("42");
        assertThat(r.topic("b").orElseThrow().getStatus()).isEqualTo(WonkoTopicStatus.SETTLED);
        assertThat(r.topic("c").orElseThrow().getStatus()).isEqualTo(WonkoTopicStatus.LATER);
        assertThat(stat.getAnswered()).isEqualTo(1);
        assertThat(stat.getSettled()).isEqualTo(1);
        assertThat(stat.getLater()).isEqualTo(1);
        assertThat(stat.getAsked()).isEqualTo(3);
    }

    @Test
    void emptyAnswerToConfirm_takesTheProposal() {
        WonkoRegister r = register(MandateLevel.ASK);
        topic(r, "timeout", WonkoTopicStatus.ASKED);
        QuestionSetDto set = QuestionSetDto.builder()
                .id("s")
                .round(1)
                .questions(new ArrayList<>(List.of(question("timeout", QuestionKind.CONFIRM, "30 s"))))
                .build();

        WonkoAnswerApplier.apply(
                r,
                set,
                List.of(QuestionAnswerDto.builder()
                        .key("timeout")
                        .outcome(QuestionOutcome.ANSWERED)
                        .build()),
                List.of(),
                1);

        assertThat(r.topic("timeout").orElseThrow().getAnswer()).isEqualTo("30 s");
    }

    @Test
    void mandateProposal_onlyTheYesOptionConfirms() {
        WonkoRegister r = register(MandateLevel.ASK);
        r.getMandate()
                .getCategories()
                .put("naming", WonkoCategory.builder().level(MandateLevel.ASK).build());
        var change = new WonkoCategoryChange(
                WonkoCategoryChange.Op.LEVEL,
                "naming",
                null,
                MandateLevel.DECIDE,
                null,
                null,
                "user settled naming twice");
        var proposal = new WonkoState.PendingProposal("mandate-proposal/0", change);
        QuestionSetDto set = QuestionSetDto.builder()
                .id("s")
                .round(2)
                .questions(new ArrayList<>(List.of(question("mandate-proposal/0", QuestionKind.CHOICE, null))))
                .build();

        WonkoAnswerApplier.apply(
                r,
                set,
                List.of(QuestionAnswerDto.builder()
                        .key("mandate-proposal/0")
                        .outcome(QuestionOutcome.ANSWERED)
                        .value("No")
                        .build()),
                List.of(proposal),
                2);
        assertThat(r.getMandate().levelFor("naming")).isEqualTo(MandateLevel.ASK);

        WonkoAnswerApplier.apply(
                r,
                set,
                List.of(QuestionAnswerDto.builder()
                        .key("mandate-proposal/0")
                        .outcome(QuestionOutcome.ANSWERED)
                        .value("Yes")
                        .build()),
                List.of(proposal),
                2);
        assertThat(r.getMandate().levelFor("naming")).isEqualTo(MandateLevel.DECIDE);
    }
}
