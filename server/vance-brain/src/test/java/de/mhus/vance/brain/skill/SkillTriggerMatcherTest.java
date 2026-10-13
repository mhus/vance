package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.skills.SkillTriggerType;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Auto-trigger filtering — the activation-level gate on top of the
 * classic trigger matching (planning/skill-activation-levels.md §7.3):
 * only the {@code trigger} level arms word activation; {@code hidden}
 * (declared, or not relevant for the process recipe), {@code manual}
 * and {@code agent} all leave it off. Also pins the standard filters
 * (already-active, whitelist, enabled) at the one place they live.
 */
class SkillTriggerMatcherTest {

    private final SkillResolver skillResolver = mock(SkillResolver.class);
    private final SkillSteerProcessor skillSteerProcessor = mock(SkillSteerProcessor.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final SkillActivationService activationService = mock(SkillActivationService.class);

    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<de.mhus.vance.brain.recipe.RecipeResolver>
            recipeProvider = mock(org.springframework.beans.factory.ObjectProvider.class);

    private final de.mhus.vance.brain.recipe.RecipeResolver recipeResolver =
            mock(de.mhus.vance.brain.recipe.RecipeResolver.class);
    private final SkillTriggerMatcher matcher = new SkillTriggerMatcher(
            skillResolver,
            skillSteerProcessor,
            sessionService,
            new SkillLevelResolver(
                    activationService,
                    sessionService,
                    skillResolver,
                    mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class),
                    recipeProvider));

    private static ResolvedSkill skill(String name, List<ResolvedSkill.Trigger> triggers) {
        return new ResolvedSkill(
                name,
                name,
                "desc",
                "1.0.0",
                triggers,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
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

    private static ResolvedSkill keywordSkill(String name, String... keywords) {
        return skill(name, List.of(new ResolvedSkill.Trigger(SkillTriggerType.KEYWORDS, null, List.of(keywords))));
    }

    private ThinkProcessDocument process() {
        SessionDocument session = new SessionDocument();
        session.setSessionId("s1");
        session.setUserId("alice");
        session.setProjectId("proj");
        when(sessionService.findBySessionId("s1")).thenReturn(Optional.of(session));
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .build();
    }

    @BeforeEach
    void noOverrides() {
        Mockito.lenient().when(recipeProvider.getIfAvailable()).thenReturn(recipeResolver);
        // Most tests run without an override document; the ones that pin
        // override behavior stub their own.
        Mockito.lenient()
                .when(activationService.overrides(any(SkillScopeContext.class)))
                .thenReturn(SkillActivationService.ActivationOverrides.EMPTY);
        Mockito.lenient()
                .when(activationService.resolve(
                        any(SkillActivationService.ActivationOverrides.class), any(ResolvedSkill.class)))
                .thenAnswer(inv -> {
                    ResolvedSkill s = inv.getArgument(1);
                    return new SkillActivationService.ActivationResolution(
                            s.frontmatterActivation(),
                            s.declaredActivation() == null
                                    ? de.mhus.vance.api.skills.SkillActivationSource.DERIVED
                                    : de.mhus.vance.api.skills.SkillActivationSource.FRONTMATTER,
                            null);
                });
    }

    @Test
    void matchingTrigger_activatesOneShot() {
        when(skillResolver.listAvailable(any(SkillScopeContext.class)))
                .thenReturn(List.of(keywordSkill("code-review", "review")));

        matcher.detectAndActivate(process(), "please review my changes");

        verify(skillSteerProcessor)
                .activate(any(), eq("code-review"), eq(true), eq(ActivationRoute.IMPLICIT), any(), any());
    }

    @Test
    void manualMode_isNeverArmed_evenWithMatchingTriggers() {
        // Explicit manual WITH triggers: the declaration disarms the
        // auto-trigger path (§3.1) — this is the filter SkillLoader can
        // only express, not enforce.
        ResolvedSkill manual = new ResolvedSkill(
                "code-review",
                "code-review",
                "desc",
                "1.0.0",
                List.of(new ResolvedSkill.Trigger(SkillTriggerType.KEYWORDS, null, List.of("review"))),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
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
                SkillActivation.MANUAL);
        when(skillResolver.listAvailable(any(SkillScopeContext.class))).thenReturn(List.of(manual));

        List<String> activated = matcher.detectAndActivate(process(), "please review my changes");

        assertThat(activated).isEmpty();
        verify(skillSteerProcessor, never()).activate(any(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void overrideToManual_disarmsAnOtherwiseTriggeredSkill() {
        when(skillResolver.listAvailable(any(SkillScopeContext.class)))
                .thenReturn(List.of(keywordSkill("code-review", "review")));
        Mockito.when(activationService.overrides(any(SkillScopeContext.class)))
                .thenReturn(new SkillActivationService.ActivationOverrides(java.util.Map.of(
                        "code-review", new SkillActivationService.Override(SkillActivation.MANUAL, SkillScope.USER))));

        // The overrides read pins the read-once contract; the effective-mode
        // stub is what the matcher actually consults.
        Mockito.when(activationService.resolve(
                        any(SkillActivationService.ActivationOverrides.class), any(ResolvedSkill.class)))
                .thenReturn(new SkillActivationService.ActivationResolution(
                        SkillActivation.MANUAL,
                        de.mhus.vance.api.skills.SkillActivationSource.OVERRIDE,
                        SkillScope.USER));
        List<String> activated = matcher.detectAndActivate(process(), "please review my changes");

        assertThat(activated).isEmpty();
    }

    @Test
    void agentMode_disarmsTriggers() {
        // At the agent level the model decides via skill_activate; a word
        // match would pre-empt that decision (§7.3).
        when(skillResolver.listAvailable(any(SkillScopeContext.class)))
                .thenReturn(List.of(withActivation(keywordSkill("code-review", "review"), SkillActivation.AGENT)));

        assertThat(matcher.detectAndActivate(process(), "please review my changes"))
                .isEmpty();
    }

    @Test
    void hiddenMode_disarmsTriggers() {
        when(skillResolver.listAvailable(any(SkillScopeContext.class)))
                .thenReturn(List.of(withActivation(keywordSkill("code-review", "review"), SkillActivation.HIDDEN)));

        assertThat(matcher.detectAndActivate(process(), "please review my changes"))
                .isEmpty();
    }

    @Test
    void notRelevantForTheRecipe_disarmsTriggers() {
        ThinkProcessDocument process = process();
        process.setRecipeName("default");
        de.mhus.vance.brain.recipe.ResolvedRecipe recipe = mock(de.mhus.vance.brain.recipe.ResolvedRecipe.class);
        when(recipe.relevantSkills()).thenReturn(List.of("!code-review"));
        when(recipeResolver.resolve("acme", "proj", "default")).thenReturn(Optional.of(recipe));
        when(skillResolver.listAvailable(any(SkillScopeContext.class)))
                .thenReturn(List.of(keywordSkill("code-review", "review"), keywordSkill("lint", "review")));

        // The excluded skill is hidden on this process, the other one is
        // relevant and keeps its derived trigger level.
        assertThat(matcher.detectAndActivate(process, "please review my changes"))
                .containsExactly("lint");
    }

    private static ResolvedSkill withActivation(ResolvedSkill s, SkillActivation activation) {
        return new ResolvedSkill(
                s.name(),
                s.title(),
                s.description(),
                s.version(),
                s.triggers(),
                s.promptExtension(),
                s.tools(),
                s.manualPaths(),
                s.referenceDocs(),
                s.scripts(),
                s.tags(),
                s.category(),
                s.enabled(),
                s.source(),
                s.activate(),
                s.deactivate(),
                s.lifecycle(),
                s.consumesArgs(),
                s.arguments(),
                s.action(),
                s.run(),
                activation);
    }

    @Test
    void alreadyActiveSkill_isSkipped() {
        ThinkProcessDocument process = process();
        process.setActiveSkills(List.of(de.mhus.vance.shared.skill.ActiveSkillRefEmbedded.builder()
                .name("code-review")
                .build()));
        when(skillResolver.listAvailable(any(SkillScopeContext.class)))
                .thenReturn(List.of(keywordSkill("code-review", "review")));

        assertThat(matcher.detectAndActivate(process, "please review my changes"))
                .isEmpty();
    }

    @Test
    void whitelistFiltersBeforeMatching() {
        ThinkProcessDocument process = process();
        process.setAllowedSkillsOverride(java.util.Set.of("other"));
        when(skillResolver.listAvailable(any(SkillScopeContext.class)))
                .thenReturn(List.of(keywordSkill("code-review", "review")));

        assertThat(matcher.detectAndActivate(process, "please review my changes"))
                .isEmpty();
    }

    @Test
    void activationFailure_doesNotFailTheTurn() {
        when(skillResolver.listAvailable(any(SkillScopeContext.class)))
                .thenReturn(List.of(keywordSkill("code-review", "review")));
        when(skillSteerProcessor.activate(any(), anyString(), anyBoolean(), any(), any(), any()))
                .thenThrow(new SkillNotAllowedByRecipeException("code-review", "analyze"));

        // No exception — a trigger miss must never fail the turn.
        assertThat(matcher.detectAndActivate(process(), "please review my changes"))
                .isEmpty();
    }

    @Test
    void blankUserText_isQuiet() {
        assertThat(matcher.detectAndActivate(process(), "  ")).isEmpty();
        verify(skillResolver, never()).listAvailable(any());
    }
}
