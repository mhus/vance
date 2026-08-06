package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.skills.SkillScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Positional binding of a skill invocation's trailing text. The
 * contract that matters: the last string argument is greedy, a missing
 * required argument fails loudly, and a skill that declares nothing
 * never sees {@code args} at all (its text goes out as a user message
 * instead — see {@code SkillSteerProcessor}).
 */
class SkillArgumentBinderTest {

    private static ResolvedSkill skill(
            boolean consumesArgs, List<ResolvedSkill.Argument> arguments) {
        return new ResolvedSkill(
                "s", "S", "desc", "1.0.0",
                List.of(), "body", List.of(), List.of(), List.of(), List.of(),
                List.of(), true, SkillScope.PROJECT, List.of(), List.of(),
                SkillLifecycle.SHOT, consumesArgs, arguments, null);
    }

    private static ResolvedSkill.Argument arg(String name, String type, boolean required) {
        return new ResolvedSkill.Argument(name, type, null, required);
    }

    @Test
    void undeclaredSkill_getsNoArgsAtAll() {
        assertThat(SkillArgumentBinder.bind(skill(false, List.of()), "some text")).isEmpty();
    }

    @Test
    void rawConsume_exposesTextAndWords() {
        Map<String, Object> args =
                SkillArgumentBinder.bind(skill(true, List.of()), "  look at PR 42 ");

        assertThat(args).containsEntry("text", "look at PR 42");
        assertThat(args.get("words")).isEqualTo(List.of("look", "at", "PR", "42"));
    }

    @Test
    void missingArgs_stillYieldEmptyTextForDeclaringSkill() {
        Map<String, Object> args = SkillArgumentBinder.bind(skill(true, List.of()), null);

        assertThat(args).containsEntry("text", "");
        assertThat(args.get("words")).isEqualTo(List.of());
    }

    @Test
    void lastStringArgument_bindsRemainderGreedily() {
        Map<String, Object> args = SkillArgumentBinder.bind(
                skill(true, List.of(arg("mode", "string", false), arg("note", "string", false))),
                "quick check the auth flow");

        assertThat(args).containsEntry("mode", "quick");
        assertThat(args).containsEntry("note", "check the auth flow");
    }

    @Test
    void nonStringLastArgument_bindsSingleToken() {
        Map<String, Object> args = SkillArgumentBinder.bind(
                skill(true, List.of(arg("depth", "integer", false))), "3 leftover words");

        assertThat(args).containsEntry("depth", 3L);
        // Surplus tokens are not an error — still reachable raw.
        assertThat(args).containsEntry("text", "3 leftover words");
    }

    @Test
    void booleanArgument_acceptsCommonSpellings() {
        Map<String, Object> args = SkillArgumentBinder.bind(
                skill(true, List.of(arg("deep", "boolean", false))), "yes");

        assertThat(args).containsEntry("deep", Boolean.TRUE);
    }

    @Test
    void optionalArgumentWithoutToken_staysUnset() {
        Map<String, Object> args = SkillArgumentBinder.bind(
                skill(true, List.of(arg("scope", "string", false))), null);

        assertThat(args).doesNotContainKey("scope");
    }

    @Test
    void requiredArgumentWithoutToken_throws() {
        ResolvedSkill s = skill(true, List.of(arg("scope", "string", true)));

        assertThatThrownBy(() -> SkillArgumentBinder.bind(s, ""))
                .isInstanceOf(SkillArgumentException.class)
                .hasMessageContaining("requires argument 'scope'");
    }

    @Test
    void unparseableTypedToken_throws() {
        ResolvedSkill s = skill(true, List.of(arg("depth", "integer", false)));

        assertThatThrownBy(() -> SkillArgumentBinder.bind(s, "deep"))
                .isInstanceOf(SkillArgumentException.class)
                .hasMessageContaining("expects type integer");
    }
}
