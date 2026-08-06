package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.skills.SkillScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Frontmatter contract of the {@code arguments:} block and the
 * shot-lifecycle body. Parsing decides whether a skill consumes its
 * invocation's trailing text at all, which is the switch that keeps the
 * text from being delivered twice.
 */
class SkillLoaderArgumentsParseTest {

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

    @Test
    void absentArguments_skillConsumesNothing() {
        ResolvedSkill s = parse(BASE, "Review the diff.");

        assertThat(s.consumesArgs()).isFalse();
        assertThat(s.arguments()).isEmpty();
    }

    @Test
    void argumentsTrue_enablesRawConsume() {
        ResolvedSkill s = parse(BASE + "\narguments: true", "Review {{ args.text }}.");

        assertThat(s.consumesArgs()).isTrue();
        assertThat(s.arguments()).isEmpty();
    }

    @Test
    void argumentsFalse_staysNonConsuming() {
        ResolvedSkill s = parse(BASE + "\narguments: false", "Review the diff.");

        assertThat(s.consumesArgs()).isFalse();
    }

    @Test
    void declaredArguments_parseNameTypeRequired() {
        ResolvedSkill s = parse(BASE + """

                arguments:
                  - name: scope
                    type: string
                    description: What to review.
                    required: true
                  - name: depth
                    type: integer""", "Review {{ args.scope }}.");

        assertThat(s.consumesArgs()).isTrue();
        assertThat(s.arguments()).hasSize(2);
        assertThat(s.arguments().get(0).name()).isEqualTo("scope");
        assertThat(s.arguments().get(0).required()).isTrue();
        assertThat(s.arguments().get(0).description()).isEqualTo("What to review.");
        // type defaults are explicit here; the second entry pins integer
        assertThat(s.arguments().get(1).type()).isEqualTo("integer");
        assertThat(s.arguments().get(1).required()).isFalse();
    }

    @Test
    void declaredArgumentWithoutType_defaultsToString() {
        ResolvedSkill s = parse(BASE + """

                arguments:
                  - name: scope""", "Review {{ args.scope }}.");

        assertThat(s.arguments().get(0).type()).isEqualTo("string");
    }

    @Test
    void declaredArgumentWithoutName_isRejected() {
        assertThatThrownBy(() -> parse(BASE + """

                arguments:
                  - type: string""", "Body."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arguments[0].name is required");
    }

    @Test
    void unknownArgumentType_isRejected() {
        assertThatThrownBy(() -> parse(BASE + """

                arguments:
                  - name: scope
                    type: decimal""", "Body."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arguments[0].type 'decimal'");
    }

    @Test
    void argumentsAsScalarString_isRejected() {
        assertThatThrownBy(() -> parse(BASE + "\narguments: scope", "Body."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'arguments' must be true or a list");
    }

    @Test
    void shotLifecycle_keepsBodyAsPromptExtension() {
        // The body stays the body at parse time — SkillSteerProcessor
        // decides that a shot skill's body is its turn-prompt.
        ResolvedSkill s = parse(BASE + "\nlifecycle: shot", "Review the diff now.");

        assertThat(s.lifecycle()).isEqualTo(SkillLifecycle.SHOT);
        assertThat(s.promptExtension()).isEqualTo("Review the diff now.");
    }
}
