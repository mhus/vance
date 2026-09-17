package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.skills.SkillScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Frontmatter contract of the {@code category:} picker-group key.
 * Display-only metadata — the parse normalises instead of rejecting so
 * hand-written YAML on both sides (skill field and category document)
 * matches, and only a type error fails the load.
 */
class SkillLoaderCategoryParseTest {

    private static final SkillLoader.SiblingReader NO_SIBLINGS = (folder, path) -> Optional.empty();

    private static ResolvedSkill parse(String frontmatter, String body) {
        String raw = "---\n" + frontmatter + "\n---\n" + body;
        return SkillLoader.parse("code-review", raw, SkillScope.VANCE, NO_SIBLINGS, "code-review");
    }

    private static final String BASE = """
            title: Code Review
            description: Review the current changes
            version: 1.0.0""";

    @Test
    void absentCategory_yieldsNull() {
        ResolvedSkill s = parse(BASE, "Review the diff.");

        assertThat(s.category()).isNull();
    }

    @Test
    void category_isNormalised() {
        ResolvedSkill s = parse(BASE + "\ncategory:   Coding  ", "Review the diff.");

        assertThat(s.category()).isEqualTo("coding");
    }

    @Test
    void blankCategory_meansNoCategory() {
        ResolvedSkill s = parse(BASE + "\ncategory: \"  \"", "Review the diff.");

        assertThat(s.category()).isNull();
    }

    @Test
    void nonStringCategory_failsTheLoad() {
        assertThatThrownBy(() -> parse(BASE + "\ncategory: 42", "Review the diff."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'category' must be a string");
    }
}
