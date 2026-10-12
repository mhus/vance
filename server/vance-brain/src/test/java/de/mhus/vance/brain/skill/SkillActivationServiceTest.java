package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.shared.document.DocumentService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The scope-level override document {@code _vance/config/skill_activation.yaml}
 * (planning/skill-activation-levels.md §3.4): user tier beats the project
 * cascade, malformed documents fail open, and the resolution precedence is
 * override → frontmatter → derived.
 */
class SkillActivationServiceTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final SkillResolver skillResolver = mock(SkillResolver.class);
    private final SkillActivationService service = new SkillActivationService(documentService, skillResolver);

    private static ResolvedSkill skill(String name, SkillActivation declared, boolean withTriggers) {
        return new ResolvedSkill(
                name,
                name,
                "desc",
                "1.0.0",
                withTriggers
                        ? List.of(new ResolvedSkill.Trigger(
                                de.mhus.vance.api.skills.SkillTriggerType.KEYWORDS, null, List.of("x")))
                        : List.of(),
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
                declared);
    }

    private static de.mhus.vance.shared.document.DocumentDocument tierDoc(String id, String project) {
        return de.mhus.vance.shared.document.DocumentDocument.builder()
                .id(id)
                .tenantId("acme")
                .projectId(project)
                .path(SkillActivationService.ACTIVATION_CONFIG_PATH)
                .build();
    }

    /** Stubs one tier document ({@code _tenant}, a project or {@code _user_<login>}). */
    private void stubTier(String project, String content) {
        de.mhus.vance.shared.document.DocumentDocument doc = tierDoc("doc-" + project, project);
        when(documentService.findByPath(eq("acme"), eq(project), eq(SkillActivationService.ACTIVATION_CONFIG_PATH)))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(content);
    }

    private void noDocumentsAnywhere() {
        Mockito.lenient()
                .when(documentService.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    private static SkillActivationService.ActivationOverrides overrides(
            String name, SkillActivation mode, SkillScope scope) {
        return new SkillActivationService.ActivationOverrides(
                Map.of(name, new SkillActivationService.Override(mode, scope)));
    }

    @Test
    void noDocument_yieldsEmptyOverrides() {
        noDocumentsAnywhere();

        assertThat(service.overrides(SkillScopeContext.of("acme", "alice", "proj"))
                        .isEmpty())
                .isTrue();
    }

    @Test
    void tiersMergePerEntry_innermostWinsPerSkill() {
        noDocumentsAnywhere();
        stubTier("_tenant", "skills:\n  code-review: manual\n  decision-frame: agent");
        stubTier("proj", "skills:\n  design-blueprint: manual\n  decision-frame: trigger");
        stubTier("_user_alice", "skills:\n  typescript-style: agent");

        SkillActivationService.ActivationOverrides overrides =
                service.overrides(SkillScopeContext.of("acme", "alice", "proj"));

        // A one-line user document must not blank out the outer tiers.
        assertThat(overrides.overrideFor("typescript-style")).isEqualTo(SkillActivation.AGENT);
        assertThat(overrides.scopeFor("typescript-style")).isEqualTo(SkillScope.USER);
        assertThat(overrides.overrideFor("design-blueprint")).isEqualTo(SkillActivation.MANUAL);
        assertThat(overrides.scopeFor("design-blueprint")).isEqualTo(SkillScope.PROJECT);
        assertThat(overrides.overrideFor("code-review")).isEqualTo(SkillActivation.MANUAL);
        assertThat(overrides.scopeFor("code-review")).isEqualTo(SkillScope.VANCE);
        // Same skill in two tiers: the inner one wins.
        assertThat(overrides.overrideFor("decision-frame")).isEqualTo(SkillActivation.TRIGGER);
        assertThat(overrides.scopeFor("decision-frame")).isEqualTo(SkillScope.PROJECT);
    }

    @Test
    void userTierBeatsProjectForTheSameSkill() {
        noDocumentsAnywhere();
        stubTier("proj", "skills:\n  code-review: agent");
        stubTier("_user_alice", "skills:\n  code-review: manual");

        SkillActivationService.ActivationOverrides overrides =
                service.overrides(SkillScopeContext.of("acme", "alice", "proj"));

        assertThat(overrides.overrideFor("code-review")).isEqualTo(SkillActivation.MANUAL);
        assertThat(overrides.scopeFor("code-review")).isEqualTo(SkillScope.USER);
    }

    @Test
    void malformedDocument_skipsOnlyItsTier() {
        noDocumentsAnywhere();
        stubTier("_tenant", "skills:\n  code-review: agent");
        stubTier("proj", "skills: [not, a, map]");

        SkillActivationService.ActivationOverrides overrides =
                service.overrides(SkillScopeContext.of("acme", null, "proj"));

        assertThat(overrides.overrideFor("code-review")).isEqualTo(SkillActivation.AGENT);
    }

    @Test
    void malformedEntry_isSkippedWhileTheRestStaysEffective() {
        noDocumentsAnywhere();
        stubTier("_tenant", "skills:\n  code-review: bogus\n  style: manual");

        SkillActivationService.ActivationOverrides overrides =
                service.overrides(SkillScopeContext.of("acme", null, "proj"));

        assertThat(overrides.overrideFor("code-review")).isNull();
        assertThat(overrides.overrideFor("style")).isEqualTo(SkillActivation.MANUAL);
    }

    @Test
    void overrideNamesAreNormalised() {
        noDocumentsAnywhere();
        stubTier("_tenant", "skills:\n  Code-Review: agent");

        assertThat(service.overrides(SkillScopeContext.of("acme", null, "proj")).overrideFor("code-review"))
                .isEqualTo(SkillActivation.AGENT);
    }

    @Test
    void withoutUser_neverReadsTheUserTier() {
        noDocumentsAnywhere();

        service.overrides(SkillScopeContext.of("acme", null, "proj"));

        verify(documentService, never()).findByPath(anyString(), Mockito.startsWith("_user_"), anyString());
    }

    @Test
    void tierDocumentsAreCached_untilEvicted() {
        noDocumentsAnywhere();
        stubTier("_tenant", "skills:\n  code-review: agent");
        SkillScopeContext scope = SkillScopeContext.of("acme", null, "proj");

        service.overrides(scope);
        service.overrides(scope);
        verify(documentService, Mockito.times(1))
                .findByPath("acme", "_tenant", SkillActivationService.ACTIVATION_CONFIG_PATH);

        stubTier("_tenant", "skills:\n  code-review: manual");
        service.evictTier("acme", "_tenant");

        assertThat(service.overrides(scope).overrideFor("code-review")).isEqualTo(SkillActivation.MANUAL);
    }

    @Test
    void resolution_overrideWinsOverDeclarationAndDerivation() {
        SkillActivationService.ActivationOverrides overrides =
                overrides("code-review", SkillActivation.AGENT, SkillScope.USER);

        SkillActivationService.ActivationResolution resolved =
                service.resolve(overrides, skill("code-review", SkillActivation.MANUAL, false));

        assertThat(resolved.mode()).isEqualTo(SkillActivation.AGENT);
        assertThat(resolved.source()).isEqualTo(de.mhus.vance.api.skills.SkillActivationSource.OVERRIDE);
        assertThat(resolved.overrideScope()).isEqualTo(SkillScope.USER);
    }

    @Test
    void resolution_declaredBeatsDerivation() {
        SkillActivationService.ActivationOverrides empty = SkillActivationService.ActivationOverrides.EMPTY;

        assertThat(service.resolve(empty, skill("a", SkillActivation.AGENT, true))
                        .source())
                .isEqualTo(de.mhus.vance.api.skills.SkillActivationSource.FRONTMATTER);
        assertThat(service.resolve(empty, skill("a", SkillActivation.AGENT, true))
                        .mode())
                .isEqualTo(SkillActivation.AGENT);
    }

    @Test
    void resolution_derivationWithoutDeclaration() {
        SkillActivationService.ActivationOverrides empty = SkillActivationService.ActivationOverrides.EMPTY;

        SkillActivationService.ActivationResolution withTriggers = service.resolve(empty, skill("a", null, true));
        SkillActivationService.ActivationResolution withoutTriggers = service.resolve(empty, skill("a", null, false));

        assertThat(withTriggers.mode()).isEqualTo(SkillActivation.TRIGGER);
        assertThat(withTriggers.source()).isEqualTo(de.mhus.vance.api.skills.SkillActivationSource.DERIVED);
        assertThat(withoutTriggers.mode()).isEqualTo(SkillActivation.MANUAL);
        assertThat(withoutTriggers.source()).isEqualTo(de.mhus.vance.api.skills.SkillActivationSource.DERIVED);
    }

    @Test
    void overrideForUnknownSkill_yieldsNullOverride_andFallsThroughToTheSkill() {
        SkillActivationService.ActivationOverrides overrides =
                overrides("other", SkillActivation.MANUAL, SkillScope.VANCE);

        SkillActivationService.ActivationResolution resolved =
                service.resolve(overrides, skill("a", SkillActivation.TRIGGER, true));

        assertThat(resolved.mode()).isEqualTo(SkillActivation.TRIGGER);
        assertThat(resolved.source()).isEqualTo(de.mhus.vance.api.skills.SkillActivationSource.FRONTMATTER);
    }
    // ─── setMode (write path, planning/skill-activation-levels.md §3.5) ───

    @Test
    void setMode_unknownSkill_refusesInsteadOfWritingADeadEntry() {
        assertThatThrownBy(() -> service.setMode(
                        "acme",
                        "alice",
                        "proj",
                        "ghost",
                        SkillActivation.AGENT,
                        de.mhus.vance.api.skills.SkillModeScope.USER,
                        de.mhus.vance.shared.permission.WriteActor.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not resolve");
        verify(documentService, never()).createText(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void setMode_userTier_createsTheOverrideDocumentWhenAbsent() {
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(skill("code-review", null, true)));
        when(documentService.findByPath(anyString(), eq("_user_alice"), anyString()))
                .thenReturn(Optional.empty());
        when(documentService.createText(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        SkillActivationService.ActivationResolution resolved = service.setMode(
                "acme",
                "alice",
                "proj",
                "code-review",
                SkillActivation.AGENT,
                de.mhus.vance.api.skills.SkillModeScope.USER,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        assertThat(resolved.mode()).isEqualTo(SkillActivation.AGENT);
        assertThat(resolved.overrideScope()).isEqualTo(SkillScope.USER);
        var content = org.mockito.ArgumentCaptor.forClass(String.class);
        var project = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(documentService)
                .createText(
                        eq("acme"),
                        project.capture(),
                        eq(SkillActivationService.ACTIVATION_CONFIG_PATH),
                        any(),
                        any(),
                        content.capture(),
                        eq("alice"),
                        any());
        assertThat(project.getValue()).isEqualTo("_user_alice");
        assertThat(content.getValue()).isEqualTo("skills:\n  code-review: agent\n");
    }

    @Test
    @SuppressWarnings("unchecked")
    void setMode_existingDocument_mergesInsteadOfReplacing() {
        when(skillResolver.resolve(any(), eq("beta"))).thenReturn(Optional.of(skill("beta", null, true)));
        de.mhus.vance.shared.document.DocumentDocument doc = tierDoc("cfg-1", "_tenant");
        when(documentService.findByPath(anyString(), eq("_tenant"), anyString()))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn("skills:\n  alpha: manual\n");

        service.setMode(
                "acme",
                null,
                null,
                "beta",
                SkillActivation.TRIGGER,
                de.mhus.vance.api.skills.SkillModeScope.TENANT,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        var content = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(documentService).update(eq("cfg-1"), any(), any(), content.capture(), any(), any());
        // alphabetical: alpha survived, beta was added
        assertThat(content.getValue()).isEqualTo("skills:\n  alpha: manual\n  beta: trigger\n");
    }

    @Test
    void setMode_projectScopeWithoutProject_refuses() {
        when(skillResolver.resolve(any(), eq("x"))).thenReturn(Optional.of(skill("x", null, true)));
        assertThatThrownBy(() -> service.setMode(
                        "acme",
                        "alice",
                        null,
                        "x",
                        SkillActivation.MANUAL,
                        de.mhus.vance.api.skills.SkillModeScope.PROJECT,
                        de.mhus.vance.shared.permission.WriteActor.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'project' override tier needs a project");
    }

    @Test
    void setMode_malformedExistingDocument_refusesInsteadOfDroppingItsEntries() {
        when(skillResolver.resolve(any(), eq("beta"))).thenReturn(Optional.of(skill("beta", null, true)));
        noDocumentsAnywhere();
        stubTier("_tenant", "skills: [not, a, map]");

        assertThatThrownBy(() -> service.setMode(
                        "acme",
                        null,
                        null,
                        "beta",
                        SkillActivation.AGENT,
                        de.mhus.vance.api.skills.SkillModeScope.TENANT,
                        de.mhus.vance.shared.permission.WriteActor.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed");
        verify(documentService, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    void setMode_shadowedByAnInnerTier_reportsTheEntryThatKeepsWinning() {
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(skill("code-review", null, true)));
        noDocumentsAnywhere();
        stubTier("_user_alice", "skills:\n  code-review: manual");

        SkillActivationService.ActivationResolution resolved = service.setMode(
                "acme",
                "alice",
                "proj",
                "code-review",
                SkillActivation.AGENT,
                de.mhus.vance.api.skills.SkillModeScope.PROJECT,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        assertThat(resolved.mode()).isEqualTo(SkillActivation.MANUAL);
        assertThat(resolved.overrideScope()).isEqualTo(SkillScope.USER);
    }
}
