package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.skills.SkillScope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Selector matching for recipe {@code advertisedSkills} (skills.md §7e):
 * exact names and {@code @label} selectors against the skills' tags.
 */
class SkillLabelSelectorTest {

    private static ResolvedSkill skill(String name, String... tags) {
        return new ResolvedSkill(
                name,
                name,
                "desc",
                "1.0.0",
                List.of(),
                "Methodology.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(tags),
                true,
                SkillScope.RESOURCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                null,
                SkillRun.INLINE);
    }

    @Test
    void selectorShape_isLabelOnlyWithAtPrefix() {
        assertThat(SkillLabelSelector.isLabelSelector("@coding")).isTrue();
        assertThat(SkillLabelSelector.isLabelSelector("  @coding ")).isTrue();
        assertThat(SkillLabelSelector.isLabelSelector("coding")).isFalse();
        assertThat(SkillLabelSelector.isLabelSelector(null)).isFalse();
        assertThat(SkillLabelSelector.labelOf(" @Coding ")).isEqualTo("coding");
        assertThat(SkillLabelSelector.labelOf("@")).isEmpty();
    }

    @Test
    void label_matchesTags_caseInsensitiveAndTrimmed() {
        assertThat(SkillLabelSelector.selects(List.of("@coding"), skill("a", " Coding ")))
                .isTrue();
        assertThat(SkillLabelSelector.selects(List.of("@CODING"), skill("b", "coding")))
                .isTrue();
        assertThat(SkillLabelSelector.selects(List.of("@coding"), skill("c", "code", "review")))
                .isFalse();
    }

    @Test
    void exactName_matchesTheNameOnly() {
        assertThat(SkillLabelSelector.selects(List.of("code-review"), skill("code-review")))
                .isTrue();
        // A plain entry is a name, never a tag.
        assertThat(SkillLabelSelector.selects(List.of("coding"), skill("x", "coding")))
                .isFalse();
    }

    @Test
    void anySelectorSuffices_andBlankEntriesAreIgnored() {
        List<String> selectors = List.of(" ", "@", "@docs", "pinned");
        assertThat(SkillLabelSelector.selects(selectors, skill("pinned"))).isTrue();
        assertThat(SkillLabelSelector.selects(selectors, skill("guide", "docs")))
                .isTrue();
        assertThat(SkillLabelSelector.selects(selectors, skill("other", "coding")))
                .isFalse();
        assertThat(SkillLabelSelector.selects(List.of(), skill("pinned"))).isFalse();
    }
}
