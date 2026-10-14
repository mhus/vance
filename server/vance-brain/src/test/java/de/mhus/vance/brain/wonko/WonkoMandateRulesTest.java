package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.wonko.WonkoCategoryChange.Op;
import de.mhus.vance.brain.wonko.WonkoMandateRules.Outcome;
import org.junit.jupiter.api.Test;

class WonkoMandateRulesTest {

    private static WonkoCategoryChange change(Op op, String category, String target, MandateLevel level) {
        return new WonkoCategoryChange(op, category, target, level, null, null, "because");
    }

    private static WonkoRegister withCategory(String name, MandateLevel level, MandateLevel ceiling, boolean locked) {
        WonkoRegister r = WonkoTestData.register(MandateLevel.CONFIRM);
        r.getMandate()
                .getCategories()
                .put(
                        name,
                        WonkoCategory.builder()
                                .level(level)
                                .ceiling(ceiling)
                                .locked(locked)
                                .build());
        return r;
    }

    @Test
    void define_newCategory_getsDefaultLevelAndIsLogged() {
        WonkoRegister r = WonkoTestData.register(MandateLevel.ASK);

        var result = WonkoMandateRules.request(r, change(Op.DEFINE, "Money Handling", null, null), 2);

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        WonkoCategory c = r.getMandate().getCategories().get("money-handling");
        assertThat(c.getLevel()).isEqualTo(MandateLevel.ASK);
        assertThat(c.isCreatedByWonko()).isTrue();
        assertThat(r.getMandateLog()).singleElement().satisfies(l -> {
            assertThat(l.getBy()).isEqualTo("wonko");
            assertThat(l.getReason()).isEqualTo("because");
        });
    }

    @Test
    void level_tightening_isApplied() {
        WonkoRegister r = withCategory("naming", MandateLevel.DECIDE, null, false);

        var result = WonkoMandateRules.request(r, change(Op.LEVEL, "naming", null, MandateLevel.ASK), 1);

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(r.getMandate().getCategories().get("naming").getLevel()).isEqualTo(MandateLevel.ASK);
    }

    @Test
    void level_looseningWithinCeiling_isApplied() {
        WonkoRegister r = withCategory("cosmetics", MandateLevel.CONFIRM, MandateLevel.DECIDE, false);

        var result = WonkoMandateRules.request(r, change(Op.LEVEL, "cosmetics", null, MandateLevel.DECIDE), 1);

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void level_looseningWithoutCeiling_becomesProposalAndChangesNothing() {
        WonkoRegister r = withCategory("api", MandateLevel.ASK, null, false);

        var result = WonkoMandateRules.request(r, change(Op.LEVEL, "api", null, MandateLevel.DECIDE), 1);

        assertThat(result.outcome()).isEqualTo(Outcome.PROPOSED);
        assertThat(r.getMandate().getCategories().get("api").getLevel()).isEqualTo(MandateLevel.ASK);
        assertThat(r.getMandateLog()).isEmpty();
    }

    @Test
    void level_lockedCategory_isRejectedEvenWhenTightening() {
        WonkoRegister r = withCategory("security", MandateLevel.CONFIRM, null, true);

        var result = WonkoMandateRules.request(r, change(Op.LEVEL, "security", null, MandateLevel.ASK), 1);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void split_childInheritsLevelCeilingAndLock() {
        WonkoRegister r = withCategory("data-model", MandateLevel.ASK, null, true);

        WonkoMandateRules.request(r, change(Op.SPLIT, "data-model", "money-handling", null), 1);

        WonkoCategory child = r.getMandate().getCategories().get("money-handling");
        assertThat(child.getLevel()).isEqualTo(MandateLevel.ASK);
        assertThat(child.isLocked()).isTrue();
    }

    @Test
    void merge_keepsTheStricterLevelAndMovesTopics() {
        WonkoRegister r = withCategory("naming", MandateLevel.DECIDE, null, false);
        r.getMandate()
                .getCategories()
                .put("api", WonkoCategory.builder().level(MandateLevel.ASK).build());
        WonkoTopic t = WonkoTestData.topic(r, "field-names", WonkoTopicStatus.OPEN);
        t.setCategory("naming");

        var result = WonkoMandateRules.request(r, change(Op.MERGE, "naming", "api", null), 1);

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(r.getMandate().getCategories()).doesNotContainKey("naming");
        assertThat(r.getMandate().getCategories().get("api").getLevel()).isEqualTo(MandateLevel.ASK);
        assertThat(t.getCategory()).isEqualTo("api");
    }

    @Test
    void classify_intoLooserCategory_isAProposal_noSelfAuthorisation() {
        WonkoRegister r = withCategory("api", MandateLevel.ASK, null, false);
        WonkoMandateRules.request(r, change(Op.DEFINE, "trivia", null, null), 1);
        WonkoMandateRules.request(r, change(Op.LEVEL, "trivia", null, MandateLevel.DECIDE), 1);
        WonkoTopic t = WonkoTestData.topic(r, "endpoint-shape", WonkoTopicStatus.OPEN);
        t.setCategory("api");

        var result = WonkoMandateRules.request(
                r, new WonkoCategoryChange(Op.CLASSIFY, "api", "trivia", null, null, "endpoint-shape", "x"), 1);

        assertThat(result.outcome()).isEqualTo(Outcome.PROPOSED);
        assertThat(t.getCategory()).isEqualTo("api");
    }

    @Test
    void confirm_appliesTheProposedLooseningAsUserChange() {
        WonkoRegister r = withCategory("api", MandateLevel.ASK, null, false);
        WonkoCategoryChange proposal = change(Op.LEVEL, "api", null, MandateLevel.CONFIRM);

        var result = WonkoMandateRules.confirm(r, proposal, 3);

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(r.getMandate().getCategories().get("api").getLevel()).isEqualTo(MandateLevel.CONFIRM);
        assertThat(r.getMandateLog())
                .singleElement()
                .extracting(WonkoMandateChange::getBy)
                .isEqualTo("user");
    }

    @Test
    void userSet_mayUnlockAndCreate() {
        WonkoRegister r = withCategory("security", MandateLevel.ASK, null, true);

        WonkoMandateRules.userSet(r, "security", MandateLevel.CONFIRM, false, 1);
        WonkoMandateRules.userSet(r, "Naming", MandateLevel.DECIDE, null, 1);

        assertThat(r.getMandate().getCategories().get("security").isLocked()).isFalse();
        assertThat(r.getMandate().levelFor("naming")).isEqualTo(MandateLevel.DECIDE);
    }
}
