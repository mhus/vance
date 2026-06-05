package de.mhus.vance.brain.slartibartfast;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Smoke-tests the PathCriteriaLifter — primarily as a regression guard
 * against the regex-compile failure that took down a Brain start
 * because {@code \b} inside a character class is not portable Java
 * regex. Each test additionally pins one behavioural property of the
 * lifter so future refactors can't silently regress.
 */
class PathCriteriaLifterTest {

    private final PathCriteriaLifter lifter = new PathCriteriaLifter();

    @Test
    void clinit_pattern_compiles() {
        // Loading the class triggers PATH_PATTERN's static
        // initialisation. If the regex is malformed (as in the
        // <clinit> ExceptionInInitializerError on the live brain),
        // this assertion will never run — JUnit reports the failure
        // before the test body executes. Either way: the test fails
        // loudly, and the fix lands before another Brain restart.
        assertThat(PathCriteriaLifter.class.getName())
                .endsWith("PathCriteriaLifter");
    }

    @Test
    void lift_extracts_path_from_claim_text_into_synthetic_criterion() {
        ArchitectState state = state(
                claim("The file essay/final-essay.md is the consolidated text "
                        + "written at the end of the pipeline."));
        List<String> lifted = lifter.lift(state);
        assertThat(lifted).containsExactly("essay/final-essay.md");
        assertThat(state.getAcceptanceCriteria())
                .hasSize(1)
                .first()
                .satisfies(c -> {
                    assertThat(c.getOrigin()).isEqualTo(CriterionOrigin.INFERRED_DOMAIN);
                    assertThat(c.getText())
                            .contains("essay/final-essay.md")
                            .contains("doc_create");
                });
    }

    @Test
    void lift_is_idempotent_against_existing_path_criterion() {
        ArchitectState state = state(
                claim("Output to essay/final-essay.md at the end."));
        // Pre-seed an existing criterion that already mentions the path.
        state.getAcceptanceCriteria().add(Criterion.builder()
                .id("cr1")
                .text("Output must land at `essay/final-essay.md`.")
                .origin(CriterionOrigin.USER_STATED)
                .build());
        List<String> lifted = lifter.lift(state);
        assertThat(lifted).isEmpty();
        assertThat(state.getAcceptanceCriteria()).hasSize(1);
    }

    @Test
    void lift_handles_quote_field_in_addition_to_text() {
        Claim c = Claim.builder()
                .id("cl1")
                .text("The pipeline persists the consolidated output.")
                .quote("`essay/final-essay.md` — der konsolidierte Fließtext")
                .build();
        ArchitectState state = state(c);
        List<String> lifted = lifter.lift(state);
        assertThat(lifted).containsExactly("essay/final-essay.md");
    }

    @Test
    void lift_deduplicates_repeated_paths_across_claims() {
        ArchitectState state = state(
                claim("Write to essay/final-essay.md at the end."),
                claim("The consolidated file is essay/final-essay.md."),
                claim("Also write essay/sources.md for the bibliography."));
        List<String> lifted = lifter.lift(state);
        assertThat(lifted)
                .containsExactlyInAnyOrder("essay/final-essay.md", "essay/sources.md");
        assertThat(state.getAcceptanceCriteria()).hasSize(2);
    }

    @Test
    void lift_ignores_prose_with_no_paths() {
        ArchitectState state = state(
                claim("Three or four chapters, balanced tone, factual."));
        List<String> lifted = lifter.lift(state);
        assertThat(lifted).isEmpty();
        assertThat(state.getAcceptanceCriteria()).isEmpty();
    }

    @Test
    void lift_does_nothing_when_state_has_no_claims() {
        ArchitectState state = new ArchitectState();
        state.setEvidenceClaims(new ArrayList<>());
        List<String> lifted = lifter.lift(state);
        assertThat(lifted).isEmpty();
    }

    @Test
    void lift_handles_null_state_defensively() {
        // Defensive — the engine should never call this with null,
        // but the helper must not throw if it happens.
        List<String> lifted = lifter.lift(null);
        assertThat(lifted).isEmpty();
    }

    // ──────────────────── helpers ────────────────────

    private static ArchitectState state(Claim... claims) {
        ArchitectState s = new ArchitectState();
        List<Claim> list = new ArrayList<>();
        for (Claim c : claims) list.add(c);
        s.setEvidenceClaims(list);
        s.setAcceptanceCriteria(new ArrayList<>());
        return s;
    }

    private static Claim claim(String text) {
        return Claim.builder().id("cl-" + Math.abs(text.hashCode())).text(text).build();
    }
}
