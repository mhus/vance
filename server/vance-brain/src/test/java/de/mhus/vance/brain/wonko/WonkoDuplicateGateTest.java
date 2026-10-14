package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The evidence is the stable anchor of a concern (plan §5.3): the model may
 * name the same contradiction differently in a recheck, but it quotes the same
 * rule. Such a finding must not come back as a new question.
 */
class WonkoDuplicateGateTest {

    private static final String DELETION = "Payment records are deleted only after the archive period has expired.";

    private static WonkoFinding finding(String key, String ref, String quote) {
        return new WonkoFinding(
                key,
                "Title " + key,
                null,
                null,
                "contradiction",
                QuestionWeight.BLOCKER,
                "?",
                QuestionKind.TEXT,
                List.of(),
                null,
                null,
                quote,
                false,
                "because",
                ref,
                quote);
    }

    private static WonkoTurn turn(WonkoRegister register, WonkoState state) {
        return new WonkoTurn(null, null, state, register, new WonkoParams(Map.of()), "goal", null, "");
    }

    @Test
    void sameRuleAndOverlappingQuote_asAnsweredTopic_isADuplicate() {
        WonkoRegister register = WonkoTestData.register(MandateLevel.CONFIRM);
        WonkoTopic answered = WonkoTestData.topic(register, "deletion/after-archive-period", WonkoTopicStatus.ANSWERED);
        answered.setEvidenceRef("rules/deletion.md");
        answered.setEvidenceQuote(DELETION);

        String dup = WonkoItemHandlers.duplicateOf(
                turn(register, new WonkoState()),
                finding(
                        "deletion-before-retention-expires",
                        "rules/deletion.md",
                        DELETION + " Earlier deletion is not permitted."));

        assertThat(dup).isEqualTo("deletion/after-archive-period");
    }

    @Test
    void sameQuoteInAnotherRule_orSameKey_isNoDuplicate() {
        WonkoRegister register = WonkoTestData.register(MandateLevel.CONFIRM);
        WonkoTopic t = WonkoTestData.topic(register, "deletion/after-archive-period", WonkoTopicStatus.ANSWERED);
        t.setEvidenceRef("rules/deletion.md");
        t.setEvidenceQuote(DELETION);
        WonkoTurn turn = turn(register, new WonkoState());

        assertThat(WonkoItemHandlers.duplicateOf(turn, finding("x", "rules/archiving.md", DELETION)))
                .isNull();
        assertThat(WonkoItemHandlers.duplicateOf(
                        turn, finding("deletion/after-archive-period", "rules/deletion.md", DELETION)))
                .as("same key is the planner's business")
                .isNull();
    }

    @Test
    void pooledFindingWithSameEvidence_isADuplicate() {
        WonkoState state = new WonkoState();
        state.getPendingFindings().add(finding("a", "rules/deletion.md", DELETION));

        assertThat(WonkoItemHandlers.duplicateOf(
                        turn(WonkoTestData.register(MandateLevel.CONFIRM), state),
                        finding("b", "rules/deletion.md", "deleted only after the archive period has expired")))
                .isEqualTo("a");
    }

    @Test
    void comparePrompt_carriesTheClarificationsAsAmendments() {
        WonkoClaim claim = new WonkoClaim();
        claim.setId("c6");
        claim.setText("Payment records are deleted after 5 years.");
        WonkoTopic t = new WonkoTopic();
        t.setTitle("Deletion after archive period");
        t.setStatus(WonkoTopicStatus.ANSWERED);
        t.setAnswer("deleted only after 10 years");

        String prompt = WonkoPrompts.comparePrompt(
                "goal", claim, List.of(), WonkoTestData.register(MandateLevel.ASK), List.of(t));

        assertThat(prompt)
                .contains(
                        "## Already clarified for this claim",
                        "judge the claim AS AMENDED",
                        "Deletion after archive period: the user answered: deleted only after 10 years");
    }

    @Test
    void withLanguage_framesTheMaterial_orLeavesItAloneWithoutSetting() {
        String lang = "## Languages\n- Chat: respond and listen in de\n";

        assertThat(WonkoItemHandlers.withLanguage(lang, "MATERIAL"))
                .startsWith(lang)
                .contains("MATERIAL")
                .endsWith("Quotes stay verbatim in the language of their source.\n");
        assertThat(WonkoItemHandlers.withLanguage("", "MATERIAL")).isEqualTo("MATERIAL");
    }
}
