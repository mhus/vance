package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.skills.SkillScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Frontmatter contract of the {@code run:} block — where a skill's
 * activation takes effect. The strict cases are those where a
 * misconfiguration would produce a skill that looks fine and does
 * nothing. See {@code planning/skill-spawn-target.md} §3.
 */
class SkillLoaderRunParseTest {

    private static final SkillLoader.SiblingReader NO_SIBLINGS =
            (folder, path) -> Optional.empty();

    private static ResolvedSkill parse(String frontmatter, String body) {
        String raw = "---\n" + frontmatter + "\n---\n" + body;
        return SkillLoader.parse("code-review", raw, SkillScope.VANCE, NO_SIBLINGS, "code-review");
    }

    private static final String BASE = """
            title: Code Review
            description: Review the current changes
            version: 1.0.0""";

    private static final String ACTION = "\naction: Review the diff now.";

    @Test
    void absentRun_staysInline() {
        ResolvedSkill s = parse(BASE, "Review the diff.");

        assertThat(s.run()).isEqualTo(SkillRun.INLINE);
        assertThat(s.run().spawns()).isFalse();
    }

    @Test
    void targetSpawn_parsesRecipeAndDefaultsInheritToNone() {
        ResolvedSkill s = parse(BASE + ACTION + """

                run:
                  target: spawn
                  recipe: code-review""", "Review methodology.");

        assertThat(s.run().spawns()).isTrue();
        assertThat(s.run().recipe()).isEqualTo("code-review");
        assertThat(s.run().inherit()).isEqualTo("none");
    }

    @Test
    void targetSpawn_explicitInheritWins() {
        ResolvedSkill s = parse(BASE + ACTION + """

                run:
                  target: spawn
                  recipe: code-review
                  inherit: chat""", "Review methodology.");

        assertThat(s.run().inherit()).isEqualTo("chat");
    }

    @Test
    void targetInline_isTheDefaultEvenWhenSpelledOut() {
        ResolvedSkill s = parse(BASE + """

                run:
                  target: inline""", "Review the diff.");

        assertThat(s.run().spawns()).isFalse();
    }

    @Test
    void targetSpawn_withoutRecipe_isRejected() {
        assertThatThrownBy(() -> parse(BASE + ACTION + """

                run:
                  target: spawn""", "Review methodology."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run.recipe");
    }

    @Test
    void targetSpawn_withoutAction_isRejected() {
        // The body is the child's system prompt, not its task — without
        // action: the spawned worker would start and idle.
        assertThatThrownBy(() -> parse(BASE + """

                run:
                  target: spawn
                  recipe: code-review""", "Review methodology."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("action");
    }

    @Test
    void targetSpawn_withShotLifecycle_isRejected() {
        assertThatThrownBy(() -> parse(BASE + ACTION + """

                lifecycle: shot
                run:
                  target: spawn
                  recipe: code-review""", "Review methodology."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lifecycle: shot");
    }

    @Test
    void unknownTarget_isRejected() {
        assertThatThrownBy(() -> parse(BASE + ACTION + """

                run:
                  target: elsewhere
                  recipe: code-review""", "Review methodology."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run.target");
    }

    @Test
    void runMustBeAMap() {
        assertThatThrownBy(() -> parse(BASE + "\nrun: spawn", "Review the diff."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'run' must be a map");
    }

    @Test
    void targetSpawn_withTriggers_loadsAnyway() {
        // Triggers never spawn (the auto-trigger path fires no turn), but
        // the explicit /skill route still works — warn, don't fail.
        ResolvedSkill s = parse(BASE + ACTION + """

                triggers:
                  - type: KEYWORDS
                    keywords: [code review]
                run:
                  target: spawn
                  recipe: code-review""", "Review methodology.");

        assertThat(s.run().spawns()).isTrue();
        assertThat(s.triggers()).hasSize(1);
    }
}
