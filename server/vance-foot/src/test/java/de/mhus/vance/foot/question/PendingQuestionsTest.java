package de.mhus.vance.foot.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.LiveRegion;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingQuestionsTest {

    private final LiveRegion liveRegion = mock(LiveRegion.class);
    private final PendingQuestions questions = new PendingQuestions(mock(ChatTerminal.class), liveRegion);

    private static String fence(String idLine) {
        return "Text\n```questions\n" + idLine
                + "questions:\n  - key: a\n    text: A?\n  - key: b\n    text: B?\n```\n";
    }

    @Test
    void assistantFence_opensTheWizard_withAPromptLabel() {
        questions.onAssistantMessage(fence(""));

        assertThat(questions.isActive()).isTrue();
        verify(liveRegion).setPromptLabel("1/2 › ");
    }

    @Test
    void setWithoutId_closesOnAnyUserMessage() {
        questions.onAssistantMessage(fence(""));

        questions.onUserMessage(null);

        assertThat(questions.isActive()).isFalse();
    }

    @Test
    void setWithId_staysOpenUntilAMessageIsMarkedAsItsAnswer() {
        questions.onAssistantMessage(fence("id: s-1\n"));

        questions.onUserMessage(Map.of());
        assertThat(questions.isActive()).as("a question back to the engine").isTrue();

        questions.onUserMessage(Map.of(PendingQuestions.META_QUESTIONS_ANSWERED, "s-1"));
        assertThat(questions.isActive()).isFalse();
    }

    @Test
    void pause_andAnswerResume() {
        questions.onAssistantMessage(fence(""));

        questions.offer(":pause");
        assertThat(questions.isActive()).isFalse();

        assertThat(questions.resume()).isTrue();
        assertThat(questions.isActive()).isTrue();
    }

    @Test
    void invalidFence_opensNothing() {
        questions.onAssistantMessage("```questions\nquestions: []\n```\n");

        assertThat(questions.isActive()).isFalse();
    }
}
