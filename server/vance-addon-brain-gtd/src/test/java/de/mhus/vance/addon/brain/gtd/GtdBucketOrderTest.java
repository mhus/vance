package de.mhus.vance.addon.brain.gtd;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.DocumentDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure ordering logic of §8b: the sequence a bucket has by default, how the
 * manifest's id list is applied on read, and how a caller's ordering of
 * <b>part</b> of a bucket is spliced back in. These functions are the entire
 * reorder contract — a wrong answer here silently drifts the manifest, so each
 * behaviour is pinned.
 */
class GtdBucketOrderTest {

    private static final GtdService SERVICE = new GtdService(null, null, null, null);

    private static GtdAction action(String id, String title) {
        return action(id, title, "");
    }

    private static GtdAction action(String id, String title, String when) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId(id);
        doc.setPath("gtd/life/actions/" + id + ".md");
        return new GtdAction(doc, "actions/" + id + ".md", false, false, null,
                title, when, null, List.of(), false);
    }

    private static List<String> ids(List<GtdAction> actions) {
        return actions.stream().map(a -> a.doc().getId()).toList();
    }

    // ── applyBucketOrder ──────────────────────────────────────────

    @Test
    void applyBucketOrder_emptyHint_fallsBackToAlphabetical() {
        List<GtdAction> bucketed = List.of(
                action("c", "Call accountant"),
                action("a", "Ask boss"),
                action("b", "Buy milk"));
        List<GtdAction> out = SERVICE.applyBucketOrder(GtdBucket.TODAY, bucketed, List.of());
        assertThat(out).extracting(GtdAction::title)
                .containsExactly("Ask boss", "Buy milk", "Call accountant");
    }

    @Test
    void applyBucketOrder_alphabeticalSortsAccentsWithTheirBaseLetter() {
        // toLowerCase-on-codepoint order would drop "Ärger" behind "Zettel".
        List<GtdAction> bucketed = List.of(
                action("z", "Zettel"),
                action("ae", "Ärger"),
                action("a", "Arbeit"));
        List<GtdAction> out = SERVICE.applyBucketOrder(GtdBucket.TODAY, bucketed, List.of());
        assertThat(out).extracting(GtdAction::title)
                .containsExactly("Arbeit", "Ärger", "Zettel");
    }

    @Test
    void applyBucketOrder_upcomingDefaultsToChronological() {
        // Upcoming means "later, in this order" — and _upcoming.md groups by
        // date regardless, so an alphabetical default would disagree with it.
        List<GtdAction> bucketed = List.of(
                action("c", "Alpha", "2026-09-30"),
                action("a", "Zulu", "2026-09-01"),
                action("b", "Mike", "2026-09-15"));
        List<GtdAction> out = SERVICE.applyBucketOrder(GtdBucket.UPCOMING, bucketed, List.of());
        assertThat(out).extracting(GtdAction::title)
                .containsExactly("Zulu", "Mike", "Alpha");
    }

    @Test
    void applyBucketOrder_hintOrdersFirst_unknownIdsDropped_restAppended() {
        List<GtdAction> bucketed = List.of(
                action("a", "Ask boss"),
                action("b", "Buy milk"),
                action("c", "Call accountant"));
        // Hint names c, then a, then a dead id; b is unnamed.
        List<GtdAction> out = SERVICE.applyBucketOrder(
                GtdBucket.TODAY, bucketed, List.of("c", "a", "dead-id"));
        assertThat(out).extracting(GtdAction::title)
                .containsExactly("Call accountant", "Ask boss", "Buy milk");
    }

    @Test
    void applyBucketOrder_hintDoesNotMutateInput() {
        List<GtdAction> bucketed = new ArrayList<>(List.of(
                action("a", "A"), action("b", "B")));
        SERVICE.applyBucketOrder(GtdBucket.TODAY, bucketed, List.of("b", "a"));
        assertThat(bucketed).extracting(GtdAction::title).containsExactly("A", "B");
    }

    @Test
    void applyBucketOrder_repeatedIdInHintIsShownOnce() {
        List<GtdAction> bucketed = List.of(action("a", "A"), action("b", "B"));
        List<GtdAction> out = SERVICE.applyBucketOrder(
                GtdBucket.TODAY, bucketed, List.of("a", "a", "b"));
        assertThat(ids(out)).containsExactly("a", "b");
    }

    // ── resyncBucketOrder ────────────────────────────────────────

    @Test
    void resyncBucketOrder_unfilteredList_takesTheCallersOrderVerbatim() {
        List<GtdAction> bucketed = List.of(
                action("a", "A"), action("b", "B"), action("c", "C"));
        List<String> out = SERVICE.resyncBucketOrder(GtdBucket.TODAY, bucketed,
                List.of("a", "b", "c"), List.of("c", "a", "b"));
        assertThat(out).containsExactly("c", "a", "b");
    }

    @Test
    void resyncBucketOrder_filteredList_permutesOnlyTheNamedSlots() {
        // The middle list was narrowed by a context filter to b and d. Dragging
        // d above b must not move a and c — they were not on screen.
        List<GtdAction> bucketed = List.of(
                action("a", "A"), action("b", "B"), action("c", "C"), action("d", "D"));
        List<String> out = SERVICE.resyncBucketOrder(GtdBucket.TODAY, bucketed,
                List.of("a", "b", "c", "d"), List.of("d", "b"));
        assertThat(out).containsExactly("a", "d", "c", "b");
    }

    @Test
    void resyncBucketOrder_dropsDeadIdsAndFoldsInUnrecordedActions() {
        // "gone" left the bucket; "c" is new and the manifest never named it.
        List<GtdAction> bucketed = List.of(
                action("a", "Ask"), action("b", "Buy"), action("c", "Call"));
        List<String> out = SERVICE.resyncBucketOrder(GtdBucket.TODAY, bucketed,
                List.of("b", "gone", "a"), List.of("b", "a"));
        // Recorded order b,a survives; c folds in at its default (alphabetical)
        // position, which is behind both.
        assertThat(out).containsExactly("b", "a", "c");
    }

    @Test
    void resyncBucketOrder_noExistingOrder_startsFromTheDefaultSequence() {
        List<GtdAction> bucketed = List.of(
                action("c", "Call"), action("a", "Ask"), action("b", "Buy"));
        List<String> out = SERVICE.resyncBucketOrder(GtdBucket.TODAY, bucketed,
                List.of(), List.of("c", "a"));
        // Default sequence is a,b,c → slots 0 and 2 are named → c,b,a.
        assertThat(out).containsExactly("c", "b", "a");
    }

    @Test
    void resyncBucketOrder_emptyRequested_returnsTheCleanedExistingOrder() {
        List<GtdAction> bucketed = List.of(action("a", "A"), action("b", "B"));
        List<String> out = SERVICE.resyncBucketOrder(GtdBucket.TODAY, bucketed,
                List.of("b", "dead", "a"), List.of());
        assertThat(out).containsExactly("b", "a");
    }

    @Test
    void resyncBucketOrder_deduplicatesRepeatedIds() {
        List<GtdAction> bucketed = List.of(action("a", "A"), action("b", "B"));
        List<String> out = SERVICE.resyncBucketOrder(GtdBucket.TODAY, bucketed,
                List.of("a", "b"), List.of("b", "b", "a"));
        assertThat(out).containsExactly("b", "a");
    }

    @Test
    void resyncBucketOrder_ignoresIdsFromOtherBuckets() {
        // The project view lists several buckets at once; the client sends only
        // the source bucket's ids, but a stale one must not slip into the list.
        List<GtdAction> bucketed = List.of(action("a", "A"), action("b", "B"));
        List<String> out = SERVICE.resyncBucketOrder(GtdBucket.TODAY, bucketed,
                List.of("a", "b"), List.of("b", "from-inbox", "a"));
        assertThat(out).containsExactly("b", "a");
    }
}
