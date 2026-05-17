package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Behavioural coverage for the system-prompt composition. The
 * INLINE/ON_DEMAND split is the key contract: ON_DEMAND must appear as
 * a listing the model can pull via {@code manual_read}, never inlined.
 */
class SkillPromptComposerTest {

    private final SkillPromptComposer composer =
            new SkillPromptComposer(new PromptTemplateRenderer());

    private static ResolvedSkill skill(
            String name, String body, List<ResolvedSkill.ReferenceDoc> docs) {
        return new ResolvedSkill(
                name, name, "desc", "1.0.0",
                List.of(), body, List.of(), List.of(),
                docs, List.of(), List.of(), true, SkillScope.PROJECT);
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

    // ─── Pebble templating in skill bodies ──────────────────────────

    @Test
    void bodyWithoutPebbleTokens_roundTripsUnchanged() {
        ResolvedSkill s = skill("plain",
                "## Default protocol\n\n1. Step.\n2. Step.", List.of());
        String out = composer.compose(List.of(s));

        assertThat(out)
                .contains("## Default protocol")
                .contains("1. Step.");
    }

    @Test
    void bodyWithTierBranch_rendersTheActiveBranch() {
        String body = "{% if tier == \"small\" %}TIGHT{% else %}FULL{% endif %}";
        ResolvedSkill s = skill("tier-aware", body, List.of());

        String small = composer.compose(List.of(s), Map.of("tier", "small"));
        assertThat(small).contains("TIGHT").doesNotContain("FULL");

        String large = composer.compose(List.of(s), Map.of("tier", "large"));
        assertThat(large).contains("FULL").doesNotContain("TIGHT");
    }

    @Test
    void bodyReferencingMode_rendersTheActiveMode() {
        String body = "Active mode: {{ mode }}.";
        ResolvedSkill s = skill("mode-aware", body, List.of());

        String executing = composer.compose(List.of(s),
                Map.of("mode", "EXECUTING"));
        assertThat(executing).contains("Active mode: EXECUTING.");

        // Lenient mode: missing variable renders empty, no crash.
        String empty = composer.compose(List.of(s), Map.of());
        assertThat(empty).contains("Active mode: .");
    }

    @Test
    void skillWithBrokenPebble_isSkippedNotPropagated() {
        ResolvedSkill broken = skill("broken",
                "{% if tier ==\n", // unterminated tag → Pebble parse error
                List.of());
        ResolvedSkill ok = skill("ok", "Plain body that works.", List.of());

        String out = composer.compose(List.of(broken, ok),
                Map.of("tier", "small"));

        // Broken skill is dropped entirely — no header, no body.
        assertThat(out)
                .doesNotContain("--- Skill: broken ---")
                .contains("--- Skill: ok ---")
                .contains("Plain body that works.");
    }

    @Test
    void referenceDocContent_isNotPebbleRendered() {
        // Reference-doc content with literal {% %} must not be parsed —
        // it's author-controlled data, not a template.
        ResolvedSkill s = skill("with-ref", "Body.", List.of(
                new ResolvedSkill.ReferenceDoc(
                        "literal", "Use {% raw %}{% if x %}{% endif %}{% endraw %}",
                        SkillReferenceDocLoadMode.INLINE, null)));

        String out = composer.compose(List.of(s), Map.of("tier", "small"));

        assertThat(out)
                .contains("Use {% raw %}{% if x %}{% endif %}{% endraw %}");
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
