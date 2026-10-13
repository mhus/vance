package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.skills.SkillScope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Relevance matching for recipe {@code relevantSkills} (skills.md §7e,
 * planning/skill-activation-levels.md §7.5): exact names, {@code @label}
 * selectors against the skills' tags, and {@code !}-exclusions of either.
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
    void selectorShape_labelsExclusionsAndMalformedEntries() {
        assertThat(SkillLabelSelector.isLabelSelector("@coding")).isTrue();
        assertThat(SkillLabelSelector.isLabelSelector("  @coding ")).isTrue();
        assertThat(SkillLabelSelector.isLabelSelector("!@coding")).isTrue();
        assertThat(SkillLabelSelector.isLabelSelector("coding")).isFalse();
        assertThat(SkillLabelSelector.isLabelSelector(null)).isFalse();
        assertThat(SkillLabelSelector.isExclusion(" !code-review")).isTrue();
        assertThat(SkillLabelSelector.isExclusion("code-review")).isFalse();
        assertThat(SkillLabelSelector.labelOf(" @Coding ")).isEqualTo("coding");
        assertThat(SkillLabelSelector.labelOf("!@Coding")).isEqualTo("coding");
        assertThat(SkillLabelSelector.labelOf("@")).isEmpty();
        assertThat(SkillLabelSelector.isMalformed("@")).isTrue();
        assertThat(SkillLabelSelector.isMalformed("!")).isTrue();
        assertThat(SkillLabelSelector.isMalformed("!@ ")).isTrue();
        assertThat(SkillLabelSelector.isMalformed("!@coding")).isFalse();
        assertThat(SkillLabelSelector.isMalformed("code-review")).isFalse();
    }

    @Test
    void absentField_makesEverythingRelevant_emptyListNothing() {
        assertThat(SkillLabelSelector.relevant(null, skill("any", "coding"))).isTrue();
        assertThat(SkillLabelSelector.relevant(List.of(), skill("any", "coding")))
                .isFalse();
    }

    @Test
    void label_matchesTags_caseInsensitiveAndTrimmed() {
        assertThat(SkillLabelSelector.relevant(List.of("@coding"), skill("a", " Coding ")))
                .isTrue();
        assertThat(SkillLabelSelector.relevant(List.of("@CODING"), skill("b", "coding")))
                .isTrue();
        assertThat(SkillLabelSelector.relevant(List.of("@coding"), skill("c", "code", "review")))
                .isFalse();
    }

    @Test
    void exactName_matchesTheNameOnly() {
        assertThat(SkillLabelSelector.relevant(List.of("code-review"), skill("code-review")))
                .isTrue();
        // A plain entry is a name, never a tag.
        assertThat(SkillLabelSelector.relevant(List.of("coding"), skill("x", "coding")))
                .isFalse();
    }

    @Test
    void exclusionsOnly_startFromEverything() {
        List<String> selectors = List.of("!@coding", "!commit-message");
        assertThat(SkillLabelSelector.relevant(selectors, skill("design-blueprint", "design")))
                .isTrue();
        assertThat(SkillLabelSelector.relevant(selectors, skill("untagged"))).isTrue();
        assertThat(SkillLabelSelector.relevant(selectors, skill("code-review", "coding")))
                .isFalse();
        assertThat(SkillLabelSelector.relevant(selectors, skill("commit-message", "git")))
                .isFalse();
    }

    @Test
    void exclusionBeatsInclusion() {
        List<String> selectors = List.of("@coding", "!commit-message");
        assertThat(SkillLabelSelector.relevant(selectors, skill("code-review", "coding")))
                .isTrue();
        assertThat(SkillLabelSelector.relevant(selectors, skill("commit-message", "coding")))
                .isFalse();
        assertThat(SkillLabelSelector.relevant(selectors, skill("design-blueprint", "design")))
                .isFalse();
    }

    @Test
    void anyInclusionSuffices_andBlankEntriesAreIgnored() {
        List<String> selectors = List.of(" ", "@", "@docs", "pinned");
        assertThat(SkillLabelSelector.relevant(selectors, skill("pinned"))).isTrue();
        assertThat(SkillLabelSelector.relevant(selectors, skill("guide", "docs")))
                .isTrue();
        assertThat(SkillLabelSelector.relevant(selectors, skill("other", "coding")))
                .isFalse();
    }
}
