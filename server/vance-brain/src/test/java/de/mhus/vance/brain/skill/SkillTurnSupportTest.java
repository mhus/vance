package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The per-turn skill hand-off shared by every skill-aware engine.
 *
 * <p>Two things matter here and neither is visible from a happy path: a
 * skill that stopped resolving mid-session must not take the turn down
 * with it, and the cascade scope has to come from the process's session —
 * getting that wrong silently resolves the wrong user's private skill.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillTurnSupportTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess-1";

    @Mock
    private SkillResolver skillResolver;

    @Mock
    private SkillPromptComposer composer;

    @Mock
    private SessionService sessionService;

    @Mock
    private SkillActivationService activationService;

    @Mock
    private RecipeResolver recipeResolver;

    @Mock
    private ObjectProvider<RecipeResolver> recipeResolverProvider;

    private SkillTurnSupport support;

    @BeforeEach
    void setUp() {
        when(recipeResolverProvider.getIfAvailable()).thenReturn(recipeResolver);
        support = new SkillTurnSupport(
                skillResolver,
                composer,
                sessionService,
                new SkillLevelResolver(
                        activationService,
                        sessionService,
                        skillResolver,
                        Mockito.mock(ThinkProcessService.class),
                        recipeResolverProvider));
    }

    private ThinkProcessDocument processWith(ActiveSkillRefEmbedded... skills) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId("proc-1");
        doc.setTenantId(TENANT);
        doc.setSessionId(SESSION);
        doc.setActiveSkills(new ArrayList<>(List.of(skills)));
        return doc;
    }

    private static ActiveSkillRefEmbedded ref(String name, @org.jspecify.annotations.Nullable String args) {
        return ActiveSkillRefEmbedded.builder().name(name).args(args).build();
    }

    private void sessionIs(String userId, String projectId) {
        SessionDocument session = new SessionDocument();
        session.setSessionId(SESSION);
        session.setUserId(userId);
        session.setProjectId(projectId);
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.of(session));
    }

    private static ResolvedSkill resolved(String name) {
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
                List.of(),
                true,
                SkillScope.VANCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                null);
    }

    // ─── resolveActive ───────────────────────────────────────────────

    @Test
    void noActiveSkills_resolvesToNothingAndTouchesNoResolver() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setTenantId(TENANT);

        assertThat(support.resolveActive(process)).isEmpty();
        verify(skillResolver, never()).resolve(any(), any());
    }

    @Test
    void skillThatNoLongerResolves_isSkippedNotFatal() {
        // The user deleted their private skill mid-session. Losing the
        // skill is acceptable; losing the turn is not.
        sessionIs("wile.coyote", "proj");
        when(skillResolver.resolve(any(), eq("gone"))).thenReturn(Optional.empty());
        when(skillResolver.resolve(any(), eq("kept"))).thenReturn(Optional.of(resolved("kept")));

        List<ResolvedSkill> out = support.resolveActive(processWith(ref("gone", null), ref("kept", null)));

        assertThat(out).extracting(ResolvedSkill::name).containsExactly("kept");
    }

    @Test
    void unknownSkillException_isSkippedNotFatal() {
        sessionIs("wile.coyote", "proj");
        when(skillResolver.resolve(any(), eq("broken"))).thenThrow(new UnknownSkillException("broken"));
        when(skillResolver.resolve(any(), eq("kept"))).thenReturn(Optional.of(resolved("kept")));

        List<ResolvedSkill> out = support.resolveActive(processWith(ref("broken", null), ref("kept", null)));

        assertThat(out).extracting(ResolvedSkill::name).containsExactly("kept");
    }

    @Test
    void blankSkillName_isIgnored() {
        sessionIs("wile.coyote", "proj");

        assertThat(support.resolveActive(processWith(ref("  ", null)))).isEmpty();
        verify(skillResolver, never()).resolve(any(), any());
    }

    // ─── scopeFor ────────────────────────────────────────────────────

    @Test
    void scope_comesFromTheProcessSession() {
        sessionIs("wile.coyote", "proj");

        SkillScopeContext scope = support.scopeFor(processWith());

        assertThat(scope.tenantId()).isEqualTo(TENANT);
        assertThat(scope.userId()).isEqualTo("wile.coyote");
        assertThat(scope.projectId()).isEqualTo("proj");
    }

    @Test
    void missingSession_stillYieldsATenantScope() {
        // A headless worker has no session. Tenant + bundled skills must
        // still resolve rather than the lookup blowing up.
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.empty());

        SkillScopeContext scope = support.scopeFor(processWith());

        assertThat(scope.tenantId()).isEqualTo(TENANT);
        assertThat(scope.userId()).isNull();
        assertThat(scope.projectId()).isNull();
    }

    @Test
    void blankUserOrProject_isNormalisedToNull() {
        SessionDocument session = new SessionDocument();
        session.setSessionId(SESSION);
        session.setUserId("  ");
        session.setProjectId("");
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.of(session));

        SkillScopeContext scope = support.scopeFor(processWith());

        assertThat(scope.userId()).isNull();
        assertThat(scope.projectId()).isNull();
    }

    // ─── rawArgsByName ───────────────────────────────────────────────

    @Test
    void rawArgs_mapSkillNameToItsInvocationText() {
        Map<String, String> args =
                SkillTurnSupport.rawArgsByName(processWith(ref("review", "--strict src/"), ref("plain", null)));

        assertThat(args).containsExactly(Map.entry("review", "--strict src/"));
    }

    @Test
    void rawArgs_emptyWithoutActiveSkills() {
        assertThat(SkillTurnSupport.rawArgsByName(new ThinkProcessDocument())).isEmpty();
    }

    @Test
    void composeSection_passesTheInvocationArgsAlong() {
        // The args are what make two activations of the same skill differ;
        // dropping them here renders every invocation identically.
        ThinkProcessDocument process = processWith(ref("review", "--strict"));
        List<ResolvedSkill> skills = List.of(resolved("review"));
        Map<String, Object> pebble = Map.of("tier", "large");

        support.composeSection(process, skills, pebble);

        verify(composer).compose(skills, pebble, Map.of("review", "--strict"));
    }

    // ─── advertiseSection ───────────────────────────────────────────

    private static ResolvedSkill agentSkill(String name) {
        return new ResolvedSkill(
                name,
                name,
                "Use when " + name,
                "1.0.0",
                List.of(),
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
                de.mhus.vance.api.skills.SkillActivation.AGENT);
    }

    private void advertiseCandidates(ResolvedSkill... skills) {
        sessionIs("wile.coyote", "proj");
        when(skillResolver.listAvailable(any())).thenReturn(List.of(skills));
        when(activationService.overrides(any())).thenReturn(SkillActivationService.ActivationOverrides.EMPTY);
        when(activationService.resolve(any(SkillActivationService.ActivationOverrides.class), any(ResolvedSkill.class)))
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
    void advertise_agentModeSkillsBecomeCards_manualAndTriggerDoNot() {
        advertiseCandidates(
                agentSkill("alpha"),
                resolved("derived-manual"), // no triggers, no declaration → manual
                new ResolvedSkill(
                        "declared-trigger",
                        "declared-trigger",
                        "desc",
                        "1.0.0",
                        List.of(),
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
                        de.mhus.vance.api.skills.SkillActivation.TRIGGER));

        String section = support.advertiseSection(processWith(), List.of());

        assertThat(section).isNotNull();
        assertThat(section).contains("## Available skills — activate with the skill_activate tool");
        assertThat(section).contains("- alpha: Use when alpha\n");
        assertThat(section).doesNotContain("derived-manual");
        assertThat(section).doesNotContain("declared-trigger");
    }

    @Test
    void advertise_respectsTheRecipeWhitelist_andDropsActiveSkills() {
        advertiseCandidates(agentSkill("alpha"), agentSkill("beta"), agentSkill("gamma"));
        ThinkProcessDocument process = processWith(ref("alpha", null));
        process.setAllowedSkillsOverride(java.util.Set.of("alpha", "gamma"));

        String section = support.advertiseSection(process, List.of(resolved("alpha")));

        // Active skills leave the list — offering what already runs makes no sense.
        assertThat(section).contains("- gamma:");
        assertThat(section).doesNotContain("alpha");
        assertThat(section).doesNotContain("beta");
    }

    @Test
    void advertise_onlyActiveSkillsEligible_yieldsNull() {
        advertiseCandidates(agentSkill("alpha"));

        assertThat(support.advertiseSection(processWith(ref("alpha", null)), List.of(resolved("alpha"))))
                .isNull();
    }

    @Test
    void advertise_recipeRelevance_listsOnlyTheAgentSkillsItSelects() {
        advertiseCandidates(
                tagged("code-review", SkillActivation.AGENT, "code", "coding"),
                tagged("lint-rules", SkillActivation.AGENT, "Coding"),
                tagged("pinned-by-name", SkillActivation.AGENT),
                tagged("unrelated", SkillActivation.AGENT, "docs"),
                tagged("manual-coding", null, "coding"));
        recipeRelevant("coder", "@coding", "pinned-by-name");
        ThinkProcessDocument process = processWith();
        process.setRecipeName("coder");

        String section = support.advertiseSection(process, List.of());

        assertThat(section).contains("- code-review:", "- lint-rules:", "- pinned-by-name:");
        // Not selected → hidden, although its mode is agent.
        assertThat(section).doesNotContain("unrelated");
        // Relevance never raises the level: a relevant manual skill stays off the list.
        assertThat(section).doesNotContain("manual-coding");
    }

    @Test
    void advertise_exclusionOnly_hidesTheExcludedGroupAndKeepsTheRest() {
        advertiseCandidates(
                tagged("code-review", SkillActivation.AGENT, "coding"),
                tagged("design-blueprint", SkillActivation.AGENT, "design"),
                tagged("untagged", SkillActivation.AGENT));
        recipeRelevant("default", "!@coding");
        ThinkProcessDocument process = processWith();
        process.setRecipeName("default");

        String section = support.advertiseSection(process, List.of());

        assertThat(section).contains("- design-blueprint:", "- untagged:");
        assertThat(section).doesNotContain("code-review");
    }

    @Test
    void advertise_emptyRelevantList_hidesEverything() {
        advertiseCandidates(tagged("code-review", SkillActivation.AGENT, "coding"));
        recipeRelevant("silent");
        ThinkProcessDocument process = processWith();
        process.setRecipeName("silent");

        assertThat(support.advertiseSection(process, List.of())).isNull();
    }

    @Test
    void advertise_relevanceStillRespectsWhitelistAndDisabled() {
        ResolvedSkill disabledTagged = new ResolvedSkill(
                "off",
                "off",
                "desc",
                "1.0.0",
                List.of(),
                "body",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("coding"),
                null,
                false,
                SkillScope.VANCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                null,
                SkillRun.INLINE,
                SkillActivation.AGENT);
        advertiseCandidates(
                tagged("code-review", SkillActivation.AGENT, "coding"),
                tagged("locked-out", SkillActivation.AGENT, "coding"),
                disabledTagged);
        recipeRelevant("coder", "@coding");
        ThinkProcessDocument process = processWith();
        process.setRecipeName("coder");
        process.setAllowedSkillsOverride(java.util.Set.of("code-review", "off"));

        String section = support.advertiseSection(process, List.of());

        assertThat(section).contains("- code-review:");
        assertThat(section).doesNotContain("locked-out").doesNotContain("- off:");
    }

    @Test
    void advertise_recipeThatNoLongerResolves_treatsEverySkillAsRelevant() {
        advertiseCandidates(agentSkill("alpha"), tagged("code-review", SkillActivation.AGENT, "coding"));
        when(recipeResolver.resolve(any(), any(), eq("gone"))).thenThrow(new IllegalStateException("broken yaml"));
        ThinkProcessDocument process = processWith();
        process.setRecipeName("gone");

        String section = support.advertiseSection(process, List.of());

        assertThat(section).contains("- alpha:", "- code-review:");
    }

    private static ResolvedSkill tagged(
            String name, @org.jspecify.annotations.Nullable SkillActivation activation, String... tags) {
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
                activation);
    }

    private void recipeRelevant(String recipeName, String... selectors) {
        ResolvedRecipe recipe = Mockito.mock(ResolvedRecipe.class);
        when(recipe.relevantSkills()).thenReturn(List.of(selectors));
        when(recipeResolver.resolve(any(), any(), eq(recipeName))).thenReturn(Optional.of(recipe));
    }

    @Test
    void advertise_isSortedByName_forCacheStableRenders() {
        advertiseCandidates(agentSkill("zulu"), agentSkill("alpha"));

        String section = support.advertiseSection(processWith(), List.of());

        int alpha = section.indexOf("- alpha:");
        int zulu = section.indexOf("- zulu:");
        assertThat(alpha).isLessThan(zulu);
    }

    @Test
    void advertise_nothingEligible_yieldsNullSoNoMessageIsAdded() {
        advertiseCandidates(resolved("plain"));

        assertThat(support.advertiseSection(processWith(), List.of())).isNull();
    }

    @Test
    void advertise_disabledSkillsAreNeverAdvertised() {
        ResolvedSkill disabled = new ResolvedSkill(
                "off",
                "off",
                "desc",
                "1.0.0",
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                SkillScope.VANCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                null,
                SkillRun.INLINE,
                de.mhus.vance.api.skills.SkillActivation.AGENT);
        advertiseCandidates(disabled);
        assertThat(support.advertiseSection(processWith(), List.of())).isNull();
    }
}
