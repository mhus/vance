package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GateChatAnswerParserTest {

    private static Optional<AnswerPayload> approval(String text) {
        return GateChatAnswerParser.parse(InboxItemType.APPROVAL, text, List.of(), "alice");
    }

    @Test
    void approval_affirmativeWord_isApproved() {
        AnswerPayload p = approval("ja").orElseThrow();

        assertThat(p.getOutcome()).isEqualTo(AnswerOutcome.DECIDED);
        assertThat(p.getValue()).containsEntry("approved", true);
        assertThat(p.getAnsweredBy()).isEqualTo("alice");
    }

    @Test
    void approval_negativeWord_isRejected() {
        assertThat(approval("nein").orElseThrow().getValue()).containsEntry("approved", false);
    }

    @Test
    void approval_isCaseAndPunctuationInsensitive() {
        assertThat(approval("  OK! ").orElseThrow().getValue()).containsEntry("approved", true);
    }

    @Test
    void approval_qualifiedYes_isNotAnAnswer() {
        // "yes, but first check X" is a conversation, not a decision — passing
        // the gate on it would approve something nobody approved.
        assertThat(approval("yes, but check the tests first")).isEmpty();
    }

    @Test
    void approval_unrelatedSentence_isNotAnAnswer() {
        assertThat(approval("what does this change do?")).isEmpty();
    }

    @Test
    void approval_emptyText_isNotAnAnswer() {
        assertThat(approval("   ")).isEmpty();
    }

    @Test
    void decision_matchingOption_isChosen() {
        Optional<AnswerPayload> p = GateChatAnswerParser.parse(
                InboxItemType.DECISION, "Retry", List.of("retry", "abort"), "bob");

        assertThat(p.orElseThrow().getValue()).containsEntry("chosen", "retry");
    }

    @Test
    void decision_unknownOption_isNotAnAnswer() {
        Optional<AnswerPayload> p = GateChatAnswerParser.parse(
                InboxItemType.DECISION, "maybe later", List.of("retry", "abort"), "bob");

        assertThat(p).isEmpty();
    }

    @Test
    void decision_withoutDeclaredOptions_isNotAnAnswer() {
        assertThat(GateChatAnswerParser.parse(
                InboxItemType.DECISION, "retry", List.of(), "bob")).isEmpty();
    }

    @Test
    void feedback_takesTheTextVerbatim() {
        AnswerPayload p = GateChatAnswerParser.parse(
                InboxItemType.FEEDBACK, "  needs a shorter intro  ", List.of(), "cara")
                .orElseThrow();

        assertThat(p.getValue()).containsEntry("text", "needs a shorter intro");
    }

    @Test
    void optionsOf_readsStringsFromPayload() {
        assertThat(GateChatAnswerParser.optionsOf(
                Map.of("options", List.of("a", 7, "b"))))
                .containsExactly("a", "b");
    }

    @Test
    void optionsOf_missingOrWrongType_isEmpty() {
        assertThat(GateChatAnswerParser.optionsOf(null)).isEmpty();
        assertThat(GateChatAnswerParser.optionsOf(Map.of("options", "nope"))).isEmpty();
    }
}
