package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.api.skills.SkillScope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behavioural coverage for the system-prompt composition. The
 * INLINE/ON_DEMAND split is the key contract: ON_DEMAND must appear as
 * a listing the model can pull via {@code manual_read}, never inlined.
 */
class SkillPromptComposerTest {

    private final SkillPromptComposer composer = new SkillPromptComposer();

    private static ResolvedSkill skill(
            String name, String body, List<ResolvedSkill.ReferenceDoc> docs) {
        return new ResolvedSkill(
                name, name, "desc", "1.0.0",
                List.of(), body, List.of(), List.of(),
                docs, List.of(), true, SkillScope.PROJECT);
    }

    @Test
    void emptyListYieldsNull() {
        assertThat(composer.compose(List.of())).isNull();
    }

    @Test
    void inlineRefsAreEmbeddedVerbatim() {
        ResolvedSkill s = skill("decision-frame", "Body.",
                List.of(new ResolvedSkill.ReferenceDoc(
                        "checklist", "1. step one\n2. step two",
                        SkillReferenceDocLoadMode.INLINE, null)));
        String out = composer.compose(List.of(s));

        assertThat(out)
                .contains("--- Reference Doc: checklist ---")
                .contains("1. step one")
                .doesNotContain("On-demand references");
    }

    @Test
    void onDemandRefsAppearAsListingNotInline() {
        ResolvedSkill s = skill("cve-analysis", "Body.",
                List.of(new ResolvedSkill.ReferenceDoc(
                        "cve/triage-workflow",
                        "should-not-leak-into-prompt",
                        SkillReferenceDocLoadMode.ON_DEMAND,
                        "full 6-step triage")));
        String out = composer.compose(List.of(s));

        assertThat(out)
                .contains("On-demand references — load via `manual_read`:")
                .contains("- cve/triage-workflow — full 6-step triage")
                .doesNotContain("should-not-leak-into-prompt")
                .doesNotContain("--- Reference Doc: cve/triage-workflow ---");
    }

    @Test
    void onDemandWithoutSummaryOmitsTheDash() {
        ResolvedSkill s = skill("x", "B.",
                List.of(new ResolvedSkill.ReferenceDoc(
                        "manual-name", "body",
                        SkillReferenceDocLoadMode.ON_DEMAND, null)));
        String out = composer.compose(List.of(s));

        assertThat(out).contains("- manual-name\n").doesNotContain("- manual-name —");
    }

    @Test
    void inlineAndOnDemandCoexistInTheSameSkill() {
        ResolvedSkill s = skill("mixed", "B.", List.of(
                new ResolvedSkill.ReferenceDoc("inline-doc", "INLINED",
                        SkillReferenceDocLoadMode.INLINE, null),
                new ResolvedSkill.ReferenceDoc("ondemand-doc", "PULLED",
                        SkillReferenceDocLoadMode.ON_DEMAND, "summary")));
        String out = composer.compose(List.of(s));

        assertThat(out)
                .contains("--- Reference Doc: inline-doc ---")
                .contains("INLINED")
                .contains("- ondemand-doc — summary")
                .doesNotContain("PULLED");
    }
}
