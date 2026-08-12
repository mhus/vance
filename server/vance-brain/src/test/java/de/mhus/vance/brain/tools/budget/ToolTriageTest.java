package de.mhus.vance.brain.tools.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the demotion step. What has to hold:
 * the surface fits afterwards, the floor is untouchable, families move
 * whole, and observed demand outranks declarations.
 */
class ToolTriageTest {

    private static final Set<String> FLOOR = Set.of("tool_list", "tool_description");

    @Test
    void surfaceThatFits_isReturnedUnchanged() {
        Set<String> primary = names("tool_list", "tool_description", "doc_read");

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), FLOOR, ToolTriage.Hints.EMPTY,
                new ToolBudget(10, 1));

        assertThat(r.changed()).isFalse();
        assertThat(r.primary()).isEqualTo(primary);
        assertThat(r.demoted()).isEmpty();
    }

    @Test
    void noLimit_isAlwaysANoOp() {
        Set<String> primary = names("a_one", "b_two", "c_three");

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), Set.of(), ToolTriage.Hints.EMPTY, ToolBudget.UNLIMITED);

        assertThat(r.changed()).isFalse();
        assertThat(r.primary()).isEqualTo(primary);
    }

    @Test
    void oversizedSurface_isCutToTheEffectiveLimit() {
        Set<String> primary = names(
                "tool_list", "tool_description",
                "doc_read", "doc_write",
                "slack_rest__a", "slack_rest__b");

        // 4 slots for classified tools (5 minus 1 reserved).
        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), FLOOR, ToolTriage.Hints.EMPTY,
                new ToolBudget(5, 1));

        assertThat(r.changed()).isTrue();
        assertThat(r.primary()).hasSizeLessThanOrEqualTo(4);
        assertThat(r.primary()).contains("tool_list", "tool_description");
    }

    @Test
    void packFamilyIsGivenUpBeforeBuiltins() {
        Set<String> primary = names(
                "doc_read", "doc_write", "slack_rest__a", "slack_rest__b");

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), Set.of(), ToolTriage.Hints.EMPTY,
                new ToolBudget(2, 0));

        assertThat(r.primary()).containsExactly("doc_read", "doc_write");
        assertThat(r.demotedFamilies()).containsExactly("slack_rest");
    }

    @Test
    void familiesMoveWhole_neverHalfAPack() {
        // 3 slots would fit doc_* plus one slack tool. Half a pack is the
        // worst state — the model starts the job and hits the wall — so
        // the whole family goes and the slot stays unused.
        Set<String> primary = names(
                "doc_read", "doc_write", "slack_rest__a", "slack_rest__b");

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), Set.of(), ToolTriage.Hints.EMPTY,
                new ToolBudget(3, 0));

        assertThat(r.primary()).containsExactly("doc_read", "doc_write");
        assertThat(r.demoted()).containsExactlyInAnyOrder("slack_rest__a", "slack_rest__b");
    }

    @Test
    void floorIsNeverDemoted_evenWhenEverythingElseGoes() {
        Set<String> primary = names(
                "tool_list", "tool_description", "doc_read", "slack_rest__a");

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), FLOOR, ToolTriage.Hints.EMPTY,
                new ToolBudget(2, 0));

        assertThat(r.primary()).containsExactlyInAnyOrder("tool_list", "tool_description");
        assertThat(r.demoted()).contains("doc_read", "slack_rest__a");
    }

    @Test
    void limitBelowTheFloor_failsLoudly() {
        Set<String> primary = names("tool_list", "tool_description", "doc_read");

        assertThatThrownBy(() -> ToolTriage.apply(
                primary, Set.of(), FLOOR, ToolTriage.Hints.EMPTY,
                new ToolBudget(2, 1)))
                .isInstanceOf(ToolBudgetException.class)
                .hasMessageContaining("mandatory floor");
    }

    @Test
    void activatedTool_outranksUnusedBuiltins() {
        // The model reached for slack_rest__a in this process — that is
        // observed task shape, and it beats a built-in nobody touched.
        Set<String> primary = names("doc_read", "doc_write");
        Set<String> activated = names("slack_rest__a");

        ToolTriage.Result r = ToolTriage.apply(
                primary, activated, Set.of(), ToolTriage.Hints.EMPTY,
                new ToolBudget(1, 0, Map.of("slack_rest__a", Instant.parse("2026-08-12T08:00:00Z")),
                        Map.of(), 0));

        assertThat(r.activated()).containsExactly("slack_rest__a");
        assertThat(r.primary()).isEmpty();
    }

    @Test
    void keepHint_holdsAToolAgainstBudgetPressure() {
        Set<String> primary = names("doc_read", "slack_rest__a", "slack_rest__b");
        ToolTriage.Hints hints = ToolTriage.Hints.ofNames(
                Set.of("slack_rest__a", "slack_rest__b"), Set.of(), Set.of());

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), Set.of(), hints, new ToolBudget(2, 0));

        assertThat(r.primary()).containsExactly("slack_rest__a", "slack_rest__b");
        assertThat(r.demoted()).containsExactly("doc_read");
    }

    @Test
    void dropFirstHint_carvesToolsOutOfTheirFamily() {
        // doc_note_add is named explicitly, so it splits off from doc_*
        // instead of dragging the whole family into the last class.
        Set<String> primary = names("doc_read", "doc_note_add");
        ToolTriage.Hints hints = ToolTriage.Hints.ofNames(
                Set.of(), Set.of(), Set.of("doc_note_add"));

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), Set.of(), hints, new ToolBudget(1, 0));

        assertThat(r.primary()).containsExactly("doc_read");
        assertThat(r.demoted()).containsExactly("doc_note_add");
    }

    @Test
    void keepWins_whenAToolIsInKeepAndDropFirst() {
        Set<String> primary = names("doc_read", "doc_write");
        ToolTriage.Hints hints = ToolTriage.Hints.ofNames(
                Set.of("doc_write"), Set.of(), Set.of("doc_write"));

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), Set.of(), hints, new ToolBudget(1, 0));

        assertThat(r.primary()).containsExactly("doc_write");
    }

    @Test
    void familyHint_appliesWhenNoNameHintDoes() {
        Set<String> primary = names("doc_read", "gmail_rest__a");
        ToolTriage.Hints hints = new ToolTriage.Hints(
                Set.of(), Set.of(), Set.of(), Set.of("gmail_rest"), Set.of("doc"));

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), Set.of(), hints, new ToolBudget(1, 0));

        assertThat(r.primary()).containsExactly("gmail_rest__a");
    }

    @Test
    void higherDemand_survivesInsideTheSameClass() {
        Set<String> primary = names("alpha_one", "beta_one");

        ToolTriage.Result r = ToolTriage.apply(
                primary, Set.of(), Set.of(), ToolTriage.Hints.EMPTY,
                new ToolBudget(1, 0, Map.of(), Map.of("beta_one", 42L), 0));

        assertThat(r.primary()).containsExactly("beta_one");
    }

    @Test
    void activationsBeyondTheCap_loseTheirTopClass() {
        // Three activations, cap of one: the two older ones drop to the
        // last class, so a long-running process cannot fill the manifest
        // with everything it once looked at.
        Set<String> activated = names("a_one", "b_one", "c_one");
        Map<String, Instant> recency = Map.of(
                "a_one", Instant.parse("2026-08-12T08:00:00Z"),
                "b_one", Instant.parse("2026-08-12T09:00:00Z"),
                "c_one", Instant.parse("2026-08-12T10:00:00Z"));

        ToolTriage.Result r = ToolTriage.apply(
                Set.of(), activated, Set.of(), ToolTriage.Hints.EMPTY,
                new ToolBudget(1, 0, recency, Map.of(), /*maxActivated*/ 1));

        assertThat(r.activated()).containsExactly("c_one");
    }

    @Test
    void identicalInput_producesIdenticalOutput() {
        Set<String> primary = names("doc_read", "doc_write", "slack_rest__a", "gmail_rest__b");
        ToolBudget budget = new ToolBudget(2, 0);

        ToolTriage.Result first = ToolTriage.apply(
                primary, Set.of(), Set.of(), ToolTriage.Hints.EMPTY, budget);
        ToolTriage.Result second = ToolTriage.apply(
                primary, Set.of(), Set.of(), ToolTriage.Hints.EMPTY, budget);

        assertThat(first.primary()).isEqualTo(second.primary());
        assertThat(first.demoted()).isEqualTo(second.demoted());
    }

    private static Set<String> names(String... values) {
        return new LinkedHashSet<>(java.util.Arrays.asList(values));
    }
}
