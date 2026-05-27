package de.mhus.vance.brain.prak;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.prak.AffectsExisting;
import de.mhus.vance.shared.prak.CrossItemRelation;
import de.mhus.vance.shared.prak.CrossItemRelationType;
import de.mhus.vance.shared.prak.Decay;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.prak.Evidence;
import de.mhus.vance.shared.prak.EvidenceRole;
import de.mhus.vance.shared.prak.ExtractedItem;
import de.mhus.vance.shared.prak.ItemCountExpectation;
import de.mhus.vance.shared.prak.ItemType;
import de.mhus.vance.shared.prak.LongTermMemoryAction;
import de.mhus.vance.shared.prak.LongTermMemoryDecision;
import de.mhus.vance.shared.prak.Scope;
import de.mhus.vance.shared.prak.WindowSpan;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrakSanitizerTest {

    private PrakProperties props;
    private PrakSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        props = new PrakProperties();
        sanitizer = new PrakSanitizer(props);
    }

    // ---- evidence validation ----

    @Test
    void evidenceValidation_dropsItemsWithFullyHalluzinatedEvidence() {
        EvaluationOutput in = output(item("evt-1", 0.9,
                List.of(ev("msg-ghost-1"), ev("msg-ghost-2"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-real-1", "msg-real-2"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).isEmpty();
        assertThat(res.metrics().droppedNoEvidence()).isEqualTo(1);
    }

    @Test
    void evidenceValidation_penalisesConfidenceForPartialHalluzination() {
        EvaluationOutput in = output(item("evt-1", 1.0,
                List.of(ev("msg-real-1"), ev("msg-ghost"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-real-1"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(1);
        assertThat(res.output().items().get(0).confidence())
                .isEqualTo(1.0 * props.getPartialEvidenceConfidencePenalty());
        assertThat(res.output().items().get(0).evidence())
                .extracting(Evidence::turnId)
                .containsExactly("msg-real-1");
        assertThat(res.metrics().confidencePenalised()).isEqualTo(1);
    }

    @Test
    void evidenceValidation_skippedWhenContextHasNoTurnIds() {
        // AutoDream-aggregation operates on ARCHIVED_CHATs, not raw turns —
        // evidence ids don't have to match a chat-turn set.
        EvaluationOutput in = output(item("evt-1", 0.9,
                List.of(ev("archived-chat-id-7"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of(), 0, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(1);
        assertThat(res.metrics().droppedNoEvidence()).isZero();
    }

    @Test
    void evidenceValidation_keepsItemsWithEmptyEvidenceArray() {
        // Items with NO evidence (empty list) are not "halluzinated" —
        // the analyzer may have produced an aggregated insight that
        // doesn't pin to a single turn. Only items with evidence pointing
        // exclusively at unknown ids get dropped.
        EvaluationOutput in = output(item("evt-1", 0.9, List.of()));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(1);
    }

    // ---- confidence floor ----

    @Test
    void confidenceFloor_dropsItemsBelowThreshold() {
        EvaluationOutput in = output(
                item("evt-1", 0.9, List.of(ev("msg-1"))),
                item("evt-2", 0.5, List.of(ev("msg-1"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(1);
        assertThat(res.output().items().get(0).id()).isEqualTo("evt-1");
        assertThat(res.metrics().droppedLowConfidence()).isEqualTo(1);
    }

    @Test
    void confidenceFloor_appliesAfterPartialPenalty() {
        // Confidence 0.7 with one halluzinated evidence (50%) penalised
        // to 0.7 * 0.7 = 0.49 → below floor 0.6 → dropped.
        EvaluationOutput in = output(item("evt-1", 0.7,
                List.of(ev("msg-real"), ev("msg-ghost"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-real"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).isEmpty();
        assertThat(res.metrics().droppedLowConfidence()).isEqualTo(1);
        assertThat(res.metrics().confidencePenalised()).isEqualTo(1);
    }

    // ---- importance-0 → SKIP ----

    @Test
    void importanceZero_forcedToSkipEvenIfAnalyzerSaidPromote() {
        ExtractedItem promotedZero = new ExtractedItem(
                "evt-1", ItemType.FACT, 0, "trivial", Scope.project("p"),
                0.9, List.of(), List.of(ev("msg-1")), null, Decay.FAST,
                LongTermMemoryDecision.promote("oops"), List.of());

        SanitizeResult res = sanitizer.sanitize(output(promotedZero),
                context(Set.of("msg-1"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(1);
        assertThat(res.output().items().get(0).longTermMemory().action())
                .isEqualTo(LongTermMemoryAction.SKIP);
    }

    // ---- cross-item supersede ----

    @Test
    void crossItemSupersede_dropsSupersededItem() {
        EvaluationOutput in = new EvaluationOutput(
                window(),
                List.of(
                        item("evt-old", 0.9, List.of(ev("msg-1"))),
                        item("evt-new", 0.9, List.of(ev("msg-2")))),
                List.of(new CrossItemRelation(
                        "evt-old", "evt-new",
                        CrossItemRelationType.SUPERSEDES_WITHIN_BATCH)));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1", "msg-2"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(1);
        assertThat(res.output().items().get(0).id()).isEqualTo("evt-new");
        assertThat(res.metrics().droppedBySupersedeWithinBatch()).isEqualTo(1);
        // resolved relations are dropped from the output
        assertThat(res.output().crossItemRelations()).isEmpty();
    }

    @Test
    void crossItemExtend_doesNotDropEither() {
        // Different labels so dedup doesn't kick in — this test isolates
        // the EXTENDS relation, not the dedup pass.
        EvaluationOutput in = new EvaluationOutput(
                window(),
                List.of(
                        item("evt-a", 0.9, "first observation",
                                List.of("topic-x"), List.of(ev("msg-1"))),
                        item("evt-b", 0.9, "second observation refining the first",
                                List.of("topic-y"), List.of(ev("msg-2")))),
                List.of(new CrossItemRelation(
                        "evt-a", "evt-b",
                        CrossItemRelationType.EXTENDS_WITHIN_BATCH)));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1", "msg-2"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(2);
    }

    // ---- dedup ----

    @Test
    void dedup_mergesItemsWithSameLabelsAndSameContent() {
        EvaluationOutput in = output(
                item("evt-low", 0.7, "User prefers terse answers",
                        List.of("user-prefs", "terseness"), List.of(ev("msg-1"))),
                item("evt-high", 0.9, "User prefers terse answers",
                        List.of("user-prefs", "terseness"), List.of(ev("msg-2"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1", "msg-2"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(1);
        assertThat(res.output().items().get(0).id()).isEqualTo("evt-high");
        assertThat(res.metrics().duplicatesMerged()).isEqualTo(1);
    }

    @Test
    void dedup_doesNotMergeItemsWithDisjointLabels() {
        EvaluationOutput in = output(
                item("evt-a", 0.9, "X",
                        List.of("label-a"), List.of(ev("msg-1"))),
                item("evt-b", 0.9, "X",
                        List.of("label-b"), List.of(ev("msg-2"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1", "msg-2"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(2);
    }

    @Test
    void dedup_doesNotMergeItemsWithSameLabelsButDifferentContent() {
        EvaluationOutput in = output(
                item("evt-a", 0.9, "User prefers terse answers",
                        List.of("user-prefs"), List.of(ev("msg-1"))),
                item("evt-b", 0.9, "User prefers verbose answers",
                        List.of("user-prefs"), List.of(ev("msg-2"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1", "msg-2"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(2);
    }

    // ---- hard-cap downgrade ----

    @Test
    void hardCap_downgradesPromoteToInboxOfferWhenOverflowing() {
        // expected range max 5, multiplier 2.0 → cap derived = 10,
        // absoluteFloor = 15 → effective hard cap = 15. Produce 16 items.
        ExtractedItem[] many = new ExtractedItem[16];
        for (int i = 0; i < 16; i++) {
            many[i] = item("evt-" + i, 0.9, "content " + i,
                    List.of("label-" + i), List.of(ev("msg-" + i)));
        }
        Set<String> turns = new java.util.HashSet<>();
        for (int i = 0; i < 16; i++) turns.add("msg-" + i);

        SanitizeResult res = sanitizer.sanitize(output(many),
                context(turns, 16, ItemCountExpectation.NORMAL));

        assertThat(res.output().items()).hasSize(16); // no drop
        assertThat(res.metrics().hardCapTriggered()).isTrue();
        assertThat(res.output().items())
                .allMatch(i -> i.longTermMemory().action()
                        == LongTermMemoryAction.INBOX_OFFER);
    }

    @Test
    void hardCap_doesNotTriggerWhenUnderCap() {
        EvaluationOutput in = output(
                item("evt-1", 0.9, List.of(ev("msg-1"))),
                item("evt-2", 0.9, List.of(ev("msg-2"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1", "msg-2"), 5, ItemCountExpectation.NORMAL));

        assertThat(res.metrics().hardCapTriggered()).isFalse();
        // unchanged actions
        assertThat(res.output().items())
                .extracting(i -> i.longTermMemory().action())
                .containsOnly(LongTermMemoryAction.PROMOTE);
    }

    // ---- coverage ----

    @Test
    void coverage_computedOverSubstantialMessageCount() {
        EvaluationOutput in = output(
                item("evt-1", 0.9, List.of(ev("msg-1"), ev("msg-2"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1", "msg-2"), 4, ItemCountExpectation.NORMAL));

        assertThat(res.metrics().evidenceCoverage()).isEqualTo(0.5);
    }

    @Test
    void coverage_flagsLowCoverageOnLargeWindow() {
        EvaluationOutput in = output(
                item("evt-1", 0.9, List.of(ev("msg-1"))));
        Set<String> turns = new java.util.HashSet<>();
        for (int i = 1; i <= 20; i++) turns.add("msg-" + i);

        SanitizeResult res = sanitizer.sanitize(in,
                context(turns, 20, ItemCountExpectation.NORMAL));

        assertThat(res.metrics().evidenceCoverage()).isEqualTo(0.05);
        assertThat(res.metrics().lowCoverage()).isTrue();
    }

    @Test
    void coverage_doesNotFlagBelowMinWindowSize() {
        EvaluationOutput in = output(
                item("evt-1", 0.9, List.of(ev("msg-1"))));

        SanitizeResult res = sanitizer.sanitize(in,
                context(Set.of("msg-1", "msg-2", "msg-3"), 3, ItemCountExpectation.NORMAL));

        assertThat(res.metrics().lowCoverage()).isFalse();
    }

    // ---- helpers ----

    @Test
    void tokenize_splitsOnWordBoundariesAndLowercases() {
        assertThat(PrakSanitizer.tokenize("User prefers terse, answers!"))
                .containsExactly("user", "prefers", "terse", "answers");
    }

    @Test
    void contentSimilarity_identicalSentencesReturnsOne() {
        assertThat(PrakSanitizer.contentSimilarity(
                "User prefers terse answers", "User prefers terse answers"))
                .isEqualTo(1.0);
    }

    @Test
    void contentSimilarity_oppositeMeaningReturnsLowJaccard() {
        // Boilerplate-dominated sentences with one discriminating word.
        // Token Jaccard: 3 shared (user, prefers, answers) / 5 unique → 0.6.
        // At default threshold 0.8 this is correctly NOT a duplicate.
        assertThat(PrakSanitizer.contentSimilarity(
                "User prefers terse answers", "User prefers verbose answers"))
                .isEqualTo(0.6);
    }

    @Test
    void labelOverlap_identicalSetsReturnOne() {
        assertThat(PrakSanitizer.labelOverlap(
                List.of("a", "b"), List.of("a", "b")))
                .isEqualTo(1.0);
    }

    @Test
    void labelOverlap_disjointSetsReturnZero() {
        assertThat(PrakSanitizer.labelOverlap(
                List.of("a"), List.of("b")))
                .isEqualTo(0.0);
    }

    @Test
    void labelOverlap_partialOverlapByLargerSet() {
        // intersect={a} size 1; max(|{a,b}|, |{a,c}|) = 2; → 0.5
        assertThat(PrakSanitizer.labelOverlap(
                List.of("a", "b"), List.of("a", "c")))
                .isEqualTo(0.5);
    }

    @Test
    void contentSimilarity_identicalReturnsOne() {
        assertThat(PrakSanitizer.contentSimilarity("foo", "foo"))
                .isEqualTo(1.0);
    }

    // ---- factories ----

    private static WindowSpan window() {
        return new WindowSpan("msg-from", "msg-to", 47);
    }

    private static Evidence ev(String turnId) {
        return new Evidence(turnId, EvidenceRole.USER, null);
    }

    private static ExtractedItem item(String id, double confidence, List<Evidence> evidence) {
        return item(id, confidence, "content " + id, List.of("label"), evidence);
    }

    private static ExtractedItem item(
            String id, double confidence, String content,
            List<String> labels, List<Evidence> evidence) {
        return new ExtractedItem(
                id,
                ItemType.FACT,
                3,
                content,
                Scope.project("test-project"),
                confidence,
                labels,
                evidence,
                null,
                Decay.SLOW,
                LongTermMemoryDecision.promote("test"),
                List.<AffectsExisting>of());
    }

    private static EvaluationOutput output(ExtractedItem... items) {
        return new EvaluationOutput(window(), List.of(items), List.of());
    }

    private static SanitizeContext context(
            Set<String> turnIds, int substantialCount, ItemCountExpectation range) {
        return new SanitizeContext(turnIds, substantialCount, range);
    }
}
