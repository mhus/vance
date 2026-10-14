package de.mhus.vance.foot.question;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionOutcome;
import org.junit.jupiter.api.Test;

class QuestionsWizardTest {

    private static QuestionsWizard wizard(String body) {
        return new QuestionsWizard(QuestionsFence.parse(body), "s-1", QuestionsAnswers.Labels.ENGLISH);
    }

    @Test
    void singleChoice_aNumberSendsAtOnce_withTheLabel() {
        var w = wizard("questions:\n  - key: v\n    text: Which?\n    kind: choice\n    options: [A, B]\n");
        assertThat(w.promptLabel()).isEqualTo("answer › ");

        var step = w.offer("2");

        assertThat(step.send()).isNotNull();
        assertThat(step.send().content()).isEqualTo("B");
        assertThat(step.send().answers().getSetId()).isEqualTo("s-1");
        assertThat(step.send().answers().getAnswers())
                .extracting(QuestionAnswerDto::getValue)
                .containsExactly("B");
    }

    @Test
    void singleQuestion_enterWithoutAssumption_asksForAnAnswer_textSends() {
        var w = wizard("questions:\n  - key: v\n    text: Name?\n");

        assertThat(w.offer("").send()).isNull();
        assertThat(w.offer("Ford").send().content()).isEqualTo("Ford");
    }

    @Test
    void severalQuestions_stepThroughOverviewReopenAndSend() {
        var w = wizard("questions:\n"
                + "  - key: r\n    text: Region?\n    kind: choice\n    options: [eu, us]\n"
                + "  - key: t\n    text: 10 years?\n    kind: confirm\n    proposal: 10 years\n"
                + "  - key: o\n    text: Owner?\n");
        assertThat(w.promptLabel()).isEqualTo("1/3 › ");

        assertThat(w.offer("1").send()).isNull(); // region = eu
        assertThat(w.offer("").send()).isNull(); // Enter confirms the assumption
        var overview = w.offer(":later");
        assertThat(w.promptLabel()).isEqualTo("send? › ");
        assertThat(overview.lines()).anyMatch(l -> l.contains("Owner? → later"));

        w.offer("1"); // reopen region
        assertThat(w.promptLabel()).isEqualTo("1/3 › ");
        w.offer("us");
        w.offer(":back");
        assertThat(w.promptLabel()).isEqualTo("1/3 › ");
        w.offer("2");
        w.offer("");
        w.offer("Ops team");
        var send = w.offer("").send();

        assertThat(send).isNotNull();
        assertThat(send.content()).isEqualTo("- Region? → us\n- 10 years? → 10 years\n- Owner? → Ops team");
        assertThat(send.answers().getAnswers())
                .extracting(QuestionAnswerDto::getOutcome)
                .containsOnly(QuestionOutcome.ANSWERED);
    }

    @Test
    void pause_leavesTheWizard_unknownCommandIsExplained() {
        var w = wizard("questions:\n  - key: a\n    text: A?\n  - key: b\n    text: B?\n");

        assertThat(w.offer(":nope").lines()).singleElement().asString().contains("Unknown command");
        assertThat(w.offer(":pause").paused()).isTrue();
    }
}
