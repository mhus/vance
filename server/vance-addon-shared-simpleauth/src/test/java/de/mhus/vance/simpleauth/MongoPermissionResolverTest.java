package de.mhus.vance.simpleauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rule matrix (R1–R7) for {@link MongoPermissionResolver}. */
class MongoPermissionResolverTest {

    private PermissionGrantService grants;
    private TeamService teamService;
    private SettingService settingService;
    private MongoPermissionResolver resolver;

    private final SecurityContext alice = SecurityContext.user("alice", "acme", List.of());
    private final SecurityContext bob = SecurityContext.user("bob", "acme", List.of("rd"));

    @BeforeEach
    void setUp() {
        grants = mock(PermissionGrantService.class);
        teamService = mock(TeamService.class);
        settingService = mock(SettingService.class);
        when(grants.forScope(any(), any(), any())).thenReturn(List.of());
        when(teamService.byMember(any(), any())).thenReturn(List.of());
        // shadow off by default → sharp verdicts in the rule-matrix tests.
        when(settingService.getBooleanValueCascade(any(), any(), any(), any(), eq(false)))
                .thenReturn(false);
        resolver = new MongoPermissionResolver(
                grants, teamService, settingService, /* metricService */ null);
    }

    private static PermissionGrantDocument grant(GrantScopeType scope, String scopeId,
            GrantSubjectType subjectType, String subjectId, GrantRole role) {
        return PermissionGrantDocument.builder()
                .tenantId("acme").scopeType(scope).scopeId(scopeId)
                .subjectType(subjectType).subjectId(subjectId).role(role).build();
    }

    @Test
    void r1_system_isAlwaysAllowed() {
        assertThat(resolver.isAllowed(SecurityContext.SYSTEM,
                new Resource.Document("acme", "proj", "_vance/secret.yaml"), Action.DELETE)).isTrue();
    }

    @Test
    void crossTenant_isDenied() {
        assertThat(resolver.isAllowed(alice,
                new Resource.Project("other-corp", "proj"), Action.READ)).isFalse();
    }

    @Test
    void r2_tenantRead_isImplicitForAnyMember() {
        assertThat(resolver.isAllowed(alice, new Resource.Tenant("acme"), Action.READ)).isTrue();
        // but tenant ADMIN needs a grant
        assertThat(resolver.isAllowed(alice, new Resource.Tenant("acme"), Action.ADMIN)).isFalse();
    }

    @Test
    void r3_projectRead_needsAGrant_writeFollowsRole() {
        // no grant → even READ denied on a normal project
        assertThat(resolver.isAllowed(alice, new Resource.Project("acme", "proj"), Action.READ)).isFalse();

        when(grants.forScope("acme", GrantScopeType.PROJECT, "proj"))
                .thenReturn(List.of(grant(GrantScopeType.PROJECT, "proj",
                        GrantSubjectType.USER, "alice", GrantRole.WRITER)));

        assertThat(resolver.isAllowed(alice, new Resource.Project("acme", "proj"), Action.READ)).isTrue();
        assertThat(resolver.isAllowed(alice, new Resource.Document("acme", "proj", "notes.md"), Action.WRITE)).isTrue();
        // WRITER is not ADMIN
        assertThat(resolver.isAllowed(alice, new Resource.Project("acme", "proj"), Action.ADMIN)).isFalse();
    }

    @Test
    void r3_teamGrant_unionsWithUserGrants() {
        when(grants.forScope("acme", GrantScopeType.PROJECT, "proj"))
                .thenReturn(List.of(grant(GrantScopeType.PROJECT, "proj",
                        GrantSubjectType.TEAM, "rd", GrantRole.WRITER)));
        // bob is in team "rd"
        assertThat(resolver.isAllowed(bob, new Resource.Document("acme", "proj", "x.md"), Action.WRITE)).isTrue();
        // alice is not in "rd"
        assertThat(resolver.isAllowed(alice, new Resource.Document("acme", "proj", "x.md"), Action.WRITE)).isFalse();
    }

    @Test
    void tenantGrant_coversEveryProject() {
        when(grants.forScope("acme", GrantScopeType.TENANT, "acme"))
                .thenReturn(List.of(grant(GrantScopeType.TENANT, "acme",
                        GrantSubjectType.USER, "alice", GrantRole.ADMIN)));
        assertThat(resolver.isAllowed(alice, new Resource.Project("acme", "any-proj"), Action.ADMIN)).isTrue();
    }

    @Test
    void r4_reservedPathWrite_needsAdmin() {
        // alice is project WRITER
        when(grants.forScope("acme", GrantScopeType.PROJECT, "proj"))
                .thenReturn(List.of(grant(GrantScopeType.PROJECT, "proj",
                        GrantSubjectType.USER, "alice", GrantRole.WRITER)));
        Resource.Document reserved = new Resource.Document("acme", "proj", "_vance/scheduler/x.yaml");
        // READ on a reserved path follows the project rule (WRITER can read)
        assertThat(resolver.isAllowed(alice, reserved, Action.READ)).isTrue();
        // WRITE on a reserved path needs ADMIN → WRITER denied
        assertThat(resolver.isAllowed(alice, reserved, Action.WRITE)).isFalse();

        when(grants.forScope("acme", GrantScopeType.PROJECT, "proj"))
                .thenReturn(List.of(grant(GrantScopeType.PROJECT, "proj",
                        GrantSubjectType.USER, "alice", GrantRole.ADMIN)));
        assertThat(resolver.isAllowed(alice, reserved, Action.WRITE)).isTrue();
    }

    @Test
    void r5_inbox_ownAssignee_orSharedTeam() {
        // own item
        assertThat(resolver.isAllowed(alice,
                new Resource.InboxItem("acme", "i1", "alice"), Action.READ)).isTrue();
        // someone else's, no shared team
        assertThat(resolver.isAllowed(alice,
                new Resource.InboxItem("acme", "i2", "carol"), Action.READ)).isFalse();
        // bob shares team "rd" with carol
        when(teamService.byMember("acme", "carol"))
                .thenReturn(List.of(team("rd")));
        assertThat(resolver.isAllowed(bob,
                new Resource.InboxItem("acme", "i3", "carol"), Action.WRITE)).isTrue();
    }

    @Test
    void r7_podlessHub_ownerHasImplicitAdmin_othersNeedTenantAdmin() {
        // alice owns _user_alice
        assertThat(resolver.isAllowed(alice,
                new Resource.Document("acme", "_user_alice", "note.md"), Action.WRITE)).isTrue();
        // alice on bob's hub → denied (no tenant admin)
        assertThat(resolver.isAllowed(alice,
                new Resource.Document("acme", "_user_bob", "note.md"), Action.READ)).isFalse();
    }

    @Test
    void r7_tenantProject_readableByMembers_writeNeedsAdmin() {
        assertThat(resolver.isAllowed(alice, new Resource.Document("acme", "_tenant", "settings.yaml"), Action.READ)).isTrue();
        assertThat(resolver.isAllowed(alice, new Resource.Document("acme", "_tenant", "settings.yaml"), Action.WRITE)).isFalse();
    }

    @Test
    void failClosed_unknownProject_noGrant_denies() {
        assertThat(resolver.isAllowed(alice,
                new Resource.Session("acme", "ghost", "s1"), Action.EXECUTE)).isFalse();
    }

    @Test
    void shadow_mode_lets_a_would_deny_through() {
        when(settingService.getBooleanValueCascade(
                eq("acme"), any(), any(), eq(MongoPermissionResolver.SHADOW_SETTING), eq(false)))
                .thenReturn(true);
        // Same check that denies sharply above — under shadow it is allowed.
        assertThat(resolver.isAllowed(alice,
                new Resource.Session("acme", "ghost", "s1"), Action.EXECUTE)).isTrue();
    }

    private static TeamDocument team(String name) {
        TeamDocument t = new TeamDocument();
        t.setName(name);
        return t;
    }
}
