package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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

    @Mock private SkillResolver skillResolver;
    @Mock private SkillPromptComposer composer;
    @Mock private SessionService sessionService;

    private SkillTurnSupport support;

    @BeforeEach
    void setUp() {
        support = new SkillTurnSupport(skillResolver, composer, sessionService);
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
                name, name, "desc", "1.0.0",
                List.of(), "body", List.of(), List.of(), List.of(), List.of(),
                List.of(), true, SkillScope.VANCE, List.of(), List.of(),
                SkillLifecycle.STICKY, false, List.of(), null);
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
        when(skillResolver.resolve(any(), eq("kept")))
                .thenReturn(Optional.of(resolved("kept")));

        List<ResolvedSkill> out = support.resolveActive(
                processWith(ref("gone", null), ref("kept", null)));

        assertThat(out).extracting(ResolvedSkill::name).containsExactly("kept");
    }

    @Test
    void unknownSkillException_isSkippedNotFatal() {
        sessionIs("wile.coyote", "proj");
        when(skillResolver.resolve(any(), eq("broken")))
                .thenThrow(new UnknownSkillException("broken"));
        when(skillResolver.resolve(any(), eq("kept")))
                .thenReturn(Optional.of(resolved("kept")));

        List<ResolvedSkill> out = support.resolveActive(
                processWith(ref("broken", null), ref("kept", null)));

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
        Map<String, String> args = SkillTurnSupport.rawArgsByName(
                processWith(ref("review", "--strict src/"), ref("plain", null)));

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
}
