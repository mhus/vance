package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.api.skills.SkillActivationSource;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Effective level = the skill's mode, filtered by the process recipe's
 * relevance (planning/skill-activation-levels.md §7.4).
 */
class SkillLevelResolverTest {

    private final SkillActivationService activationService = mock(SkillActivationService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final RecipeResolver recipeResolver = mock(RecipeResolver.class);
    private final SkillResolver skillResolver = mock(SkillResolver.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RecipeResolver> recipeProvider = mock(ObjectProvider.class);

    private final SkillLevelResolver resolver = new SkillLevelResolver(
            activationService, sessionService, skillResolver, thinkProcessService, recipeProvider);

    private final SkillScopeContext scope = SkillScopeContext.of("acme", "alice", "proj");

    @BeforeEach
    void setUp() {
        when(recipeProvider.getIfAvailable()).thenReturn(recipeResolver);
        when(activationService.overrides(any()))
                .thenReturn(new SkillActivationService.ActivationOverrides(Map.of(
                        "overridden", new SkillActivationService.Override(SkillActivation.AGENT, SkillScope.USER))));
        when(activationService.resolve(any(SkillActivationService.ActivationOverrides.class), any(ResolvedSkill.class)))
                .thenAnswer(inv -> {
                    SkillActivationService.ActivationOverrides o = inv.getArgument(0);
                    ResolvedSkill s = inv.getArgument(1);
                    SkillActivation override = o.overrideFor(s.name());
                    return override != null
                            ? new SkillActivationService.ActivationResolution(
                                    override, SkillActivationSource.OVERRIDE, o.scopeFor(s.name()))
                            : new SkillActivationService.ActivationResolution(
                                    s.frontmatterActivation(), SkillActivationSource.FRONTMATTER, null);
                });
    }

    private static ResolvedSkill skill(String name, @Nullable SkillActivation activation, String... tags) {
        return new ResolvedSkill(
                name,
                name,
                "desc",
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
                activation);
    }

    private ThinkProcessDocument process(@Nullable String recipeName) {
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .recipeName(recipeName)
                .build();
    }

    private void recipe(String name, @Nullable List<String> relevant) {
        ResolvedRecipe recipe = mock(ResolvedRecipe.class);
        when(recipe.relevantSkills()).thenReturn(relevant);
        when(recipeResolver.resolve(eq("acme"), eq("proj"), eq(name))).thenReturn(Optional.of(recipe));
    }

    @Test
    void withoutRecipe_levelIsTheMode() {
        SkillLevelResolver.Levels levels = resolver.forProcess(process(null), scope);

        assertThat(levels.levelOf(skill("a", SkillActivation.AGENT))).isEqualTo(SkillActivation.AGENT);
        assertThat(levels.levelOf(skill("m", null))).isEqualTo(SkillActivation.MANUAL);
        assertThat(levels.levelOf(skill("h", SkillActivation.HIDDEN))).isEqualTo(SkillActivation.HIDDEN);
        assertThat(levels.levelOf(skill("overridden", SkillActivation.MANUAL))).isEqualTo(SkillActivation.AGENT);
    }

    @Test
    void notRelevant_isHidden_butKeepsTheModeForTheDropdown() {
        recipe("default", List.of("!@coding"));
        SkillLevelResolver.Levels levels = resolver.forProcess(process("default"), scope);

        SkillLevelResolver.Level level = levels.of(skill("code-review", SkillActivation.AGENT, "coding"));

        assertThat(level.level()).isEqualTo(SkillActivation.HIDDEN);
        assertThat(level.relevant()).isFalse();
        assertThat(level.activation().mode()).isEqualTo(SkillActivation.AGENT);
        assertThat(levels.levelOf(skill("design", SkillActivation.AGENT, "design")))
                .isEqualTo(SkillActivation.AGENT);
    }

    @Test
    void relevance_neverRaisesAHiddenMode() {
        recipe("coder", List.of("@coding"));
        SkillLevelResolver.Levels levels = resolver.forProcess(process("coder"), scope);

        assertThat(levels.levelOf(skill("experimental", SkillActivation.HIDDEN, "coding")))
                .isEqualTo(SkillActivation.HIDDEN);
    }

    @Test
    void recipeWithoutField_orUnresolvable_makesEverythingRelevant() {
        recipe("plain", null);
        when(recipeResolver.resolve(eq("acme"), eq("proj"), eq("broken"))).thenThrow(new IllegalStateException("x"));

        assertThat(resolver.forProcess(process("plain"), scope).levelOf(skill("a", SkillActivation.AGENT, "coding")))
                .isEqualTo(SkillActivation.AGENT);
        assertThat(resolver.forProcess(process("broken"), scope).levelOf(skill("a", SkillActivation.AGENT, "coding")))
                .isEqualTo(SkillActivation.AGENT);
    }

    @Test
    void hiddenSkillNames_listsWhatIsHiddenOnTheProcess() {
        recipe("default", List.of("!@coding"));
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process("default")));
        when(skillResolver.listAvailable(any()))
                .thenReturn(List.of(
                        skill("code-review", SkillActivation.AGENT, "coding"),
                        skill("design", SkillActivation.AGENT, "design"),
                        skill("experimental", SkillActivation.HIDDEN)));

        assertThat(resolver.hiddenSkillNames("p1")).containsExactlyInAnyOrder("code-review", "experimental");
        assertThat(resolver.hiddenSkillNames(null)).isEmpty();
        assertThat(resolver.hiddenSkillNames("unknown")).isEmpty();
    }
}
