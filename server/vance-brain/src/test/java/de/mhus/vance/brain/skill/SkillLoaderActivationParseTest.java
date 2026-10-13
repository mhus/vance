package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.api.skills.SkillScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Frontmatter contract of the {@code activation:} mode
 * (planning/skill-activation-levels.md §3.4): explicit values parse,
 * absence derives from {@code triggers:} presence, and a typo fails the
 * load — a wrong value would silently hide the skill ("manual") or
 * advertise a skill the author never meant to offer.
 */
class SkillLoaderActivationParseTest {

    private static final SkillLoader.SiblingReader NO_SIBLINGS = (folder, path) -> Optional.empty();

    private static ResolvedSkill parse(String frontmatter, String body) {
        String raw = "---\n" + frontmatter + "\n---\n" + body;
        return SkillLoader.parse("code-review", raw, SkillScope.VANCE, NO_SIBLINGS, "code-review");
    }

    private static final String BASE = """
            title: Code Review
            description: Review the current changes
            version: 1.0.0""";

    private static final String WITH_TRIGGERS = """
            triggers:
              - type: KEYWORDS
                keywords: [review, code review]""";

    @Test
    void explicitModes_parse() {
        assertThat(parse(BASE + "\nactivation: hidden", "Body").declaredActivation())
                .isEqualTo(SkillActivation.HIDDEN);
        assertThat(parse(BASE + "\nactivation: manual", "Body").declaredActivation())
                .isEqualTo(SkillActivation.MANUAL);
        assertThat(parse(BASE + "\nactivation: trigger", "Body").declaredActivation())
                .isEqualTo(SkillActivation.TRIGGER);
        assertThat(parse(BASE + "\nactivation: agent", "Body").declaredActivation())
                .isEqualTo(SkillActivation.AGENT);
    }

    @Test
    void modeIsCaseInsensitive_andTrimmed() {
        assertThat(parse(BASE + "\nactivation:   AGENT ", "Body").declaredActivation())
                .isEqualTo(SkillActivation.AGENT);
    }

    @Test
    void absentModeWithTriggers_derivesTrigger() {
        ResolvedSkill s = parse(BASE + "\n" + WITH_TRIGGERS, "Body");

        assertThat(s.declaredActivation()).isNull();
        assertThat(s.frontmatterActivation()).isEqualTo(SkillActivation.TRIGGER);
    }

    @Test
    void absentModeWithoutTriggers_derivesManual() {
        ResolvedSkill s = parse(BASE, "Body");

        assertThat(s.declaredActivation()).isNull();
        assertThat(s.frontmatterActivation()).isEqualTo(SkillActivation.MANUAL);
    }

    @Test
    void declaredManual_disarmsTriggersAtFrontmatterLevel() {
        // A skill may declare manual WITH triggers — the declaration wins
        // over the derivation, and the trigger matcher's mode filter skips
        // it (pinned by SkillTriggerMatcherTest).
        ResolvedSkill s = parse(BASE + "\n" + WITH_TRIGGERS + "\nactivation: manual", "Body");

        assertThat(s.declaredActivation()).isEqualTo(SkillActivation.MANUAL);
        assertThat(s.frontmatterActivation()).isEqualTo(SkillActivation.MANUAL);
    }

    @Test
    void unknownMode_failsTheLoad() {
        assertThatThrownBy(() -> parse(BASE + "\nactivation: always", "Body"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown activation 'always'")
                .hasMessageContaining("expected hidden|manual|trigger|agent");
    }

    @Test
    void nonStringMode_failsTheLoad() {
        assertThatThrownBy(() -> parse(BASE + "\nactivation: 42", "Body"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'activation' must be a string");
    }

    @Test
    void lifecycle_defaultsToTurn_stickyAndShotOnDeclaration() {
        assertThat(parse(BASE, "Body").lifecycle()).isEqualTo(SkillLifecycle.TURN);
        assertThat(parse(BASE + "\nlifecycle: turn", "Body").lifecycle()).isEqualTo(SkillLifecycle.TURN);
        assertThat(parse(BASE + "\nlifecycle: sticky", "Body").lifecycle()).isEqualTo(SkillLifecycle.STICKY);
        assertThat(parse(BASE + "\nlifecycle: shot", "Body").lifecycle()).isEqualTo(SkillLifecycle.SHOT);
    }

    @Test
    void delegation_defaultsToCarry_stayOnDeclaration_typoFails() {
        assertThat(parse(BASE, "Body").delegation()).isEqualTo(SkillDelegation.CARRY);
        assertThat(parse(BASE + "\ndelegation: stay", "Body").delegation()).isEqualTo(SkillDelegation.STAY);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parse(BASE + "\ndelegation: keep", "Body"))
                .hasMessageContaining("expected carry|stay");
    }
}
