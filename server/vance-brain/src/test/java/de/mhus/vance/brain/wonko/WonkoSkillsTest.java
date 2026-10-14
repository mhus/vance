package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SingleShotSkillComposer;
import de.mhus.vance.brain.skill.SkillLifecycle;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillRun;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wonko's model calls carry the skills its recipe selects by label or name
 * and the skills activated on the process — only those that compose into a
 * single-shot prompt, the others named with their reason.
 */
class WonkoSkillsTest {

    private final SkillResolver resolver = mock(SkillResolver.class);
    private final SingleShotSkillComposer composer = mock(SingleShotSkillComposer.class);
    private final WonkoSkills skills = new WonkoSkills(resolver, composer);
    private final ThinkProcessDocument process = new ThinkProcessDocument();

    @BeforeEach
    void setUp() {
        process.setTenantId("acme");
        process.setProjectId("p");
        when(resolver.listAvailable(any()))
                .thenReturn(List.of(
                        skill("bank-rules", "wonko", "compliance"),
                        skill("code-review", "review"),
                        skill("wording", "wonko")));
    }

    @Test
    void labelSelectsTaggedSkills_exclusionWins() {
        var selection = skills.select(process, "u", List.of("@wonko", "!wording"));

        assertThat(selection.names()).containsExactly("bank-rules");
        assertThat(selection.skipped()).isEmpty();
    }

    @Test
    void activeSkillsComeOnTop_noSelectorsSelectNothingFromTheCatalog() {
        ActiveSkillRefEmbedded active = new ActiveSkillRefEmbedded();
        active.setName("wording");
        process.getActiveSkills().add(active);

        assertThat(skills.select(process, "u", List.of()).names()).containsExactly("wording");
    }

    @Test
    void notComposable_orNotWhitelisted_isNamedNotDropped() {
        process.setAllowedSkillsOverride(Set.of("bank-rules", "wording"));
        when(composer.resolveComposable(any(), eq(List.of("wording"))))
                .thenThrow(new IllegalArgumentException("skill 'wording' declares ON_DEMAND reference docs"));

        var selection = skills.select(process, "u", List.of("@wonko", "@review"));

        assertThat(selection.names()).containsExactly("bank-rules");
        assertThat(selection.skipped())
                .containsExactlyInAnyOrder(
                        "skill 'code-review' is not in the recipe's allowedSkills",
                        "skill 'wording' declares ON_DEMAND reference docs");
    }

    private static ResolvedSkill skill(String name, String... tags) {
        return new ResolvedSkill(
                name,
                name,
                "Use when " + name,
                "1.0.0",
                List.of(),
                "body",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(tags),
                null,
                true,
                SkillScope.VANCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                null,
                SkillRun.INLINE,
                null);
    }
}
