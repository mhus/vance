package de.mhus.vance.shared.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The five stages of {@link PermissionService#check} and the role-shaped helpers. */
class PermissionServiceTest {

    private static SecurityContext user(String name) {
        return SecurityContext.user(name, "acme", List.of());
    }

    // ── construction ──

    @Test
    void construction_failsFast_whenNoProviderPresent() {
        assertThatThrownBy(() -> new PermissionService(List.of(), (t, u) -> List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one permission provider")
                .hasMessageContaining("found 0");
    }

    @Test
    void construction_failsFast_whenMultipleProvidersPresent() {
        assertThatThrownBy(() -> new PermissionService(
                        List.of(mock(PermissionProvider.class), mock(PermissionProvider.class)), (t, u) -> List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 2");
    }

    // ── stage 2: SYSTEM trust ──

    @Test
    void systemSubject_isAllowed_withoutAnyRole() {
        PermissionService service = new FakePermissionProvider().failing().service();

        assertThat(service.check(SecurityContext.SYSTEM, new Resource.Project("acme", "_tenant"), Action.DELETE))
                .isTrue();
    }

    @Test
    void systemReason_allowsAUserWriteTheRolesWouldDeny() {
        PermissionService service = new FakePermissionProvider().service();

        assertThat(service.check(
                        user("alice"),
                        new Resource.Document("acme", "proj", "_vance/x.yaml"),
                        Action.WRITE,
                        WriteReason.SYSTEM))
                .isTrue();
    }

    // ── stage 4 + 5 ──

    @Test
    void enforce_throwsWithTheCheckedFacts_whenDenied() {
        PermissionService service = new FakePermissionProvider().service();
        SecurityContext alice = user("alice");
        Resource resource = new Resource.Project("acme", "proj");

        assertThatThrownBy(() -> service.enforce(alice, resource, Action.WRITE))
                .isInstanceOfSatisfying(PermissionDeniedException.class, ex -> {
                    assertThat(ex.getSubject()).isSameAs(alice);
                    assertThat(ex.getResource()).isSameAs(resource);
                    assertThat(ex.getAction()).isEqualTo(Action.WRITE);
                });
    }

    @Test
    void member_writesTheirProject() {
        PermissionService service = new FakePermissionProvider()
                .member("proj", "alice", ProjectRole.MEMBER)
                .service();

        assertThat(service.check(user("alice"), new Resource.Project("acme", "proj"), Action.WRITE))
                .isTrue();
        assertThat(service.check(user("alice"), new Resource.Project("acme", "proj"), Action.ADMIN))
                .isFalse();
    }

    @Test
    void veto_takesAwayWhatTheTableAllows() {
        PermissionService service = new FakePermissionProvider()
                .tenant("alice", TenantRole.TENANT_ADMIN)
                .veto((r, a) -> r instanceof Resource.Setting)
                .service();

        assertThat(service.check(user("alice"), new Resource.Setting("acme", "tenant", "acme", "k"), Action.WRITE))
                .isFalse();
        assertThat(service.check(user("alice"), new Resource.Project("acme", "proj"), Action.WRITE))
                .isTrue();
    }

    @Test
    void veto_cannotWidenWhatTheTableDenies() {
        PermissionService service =
                new FakePermissionProvider().veto((r, a) -> false).service();

        assertThat(service.check(user("alice"), new Resource.Tenant("acme"), Action.ADMIN))
                .isFalse();
    }

    @Test
    void failingProvider_deniesEverything() {
        PermissionService service = new FakePermissionProvider().failing().service();

        assertThat(service.check(user("alice"), new Resource.Tenant("acme"), Action.READ))
                .isFalse();
    }

    // ── stage 3: core preconditions ──

    @Test
    void crossTenant_isDenied_evenForATenantAdmin() {
        PermissionService service = new FakePermissionProvider()
                .tenant("alice", TenantRole.TENANT_ADMIN)
                .service();

        assertThat(service.check(user("alice"), new Resource.Project("other", "proj"), Action.READ))
                .isFalse();
    }

    @Test
    void tenantProject_isReadableByEveryMember() {
        PermissionService service = new FakePermissionProvider().service();

        assertThat(service.check(
                        user("alice"), new Resource.Document("acme", "_tenant", "_vance/recipes/x.yaml"), Action.READ))
                .isTrue();
    }

    @Test
    void tenantProject_isWrittenByTenantAdminOnly() {
        PermissionService service = new FakePermissionProvider()
                .tenant("adm", TenantRole.ADMIN)
                .tenant("root", TenantRole.TENANT_ADMIN)
                .service();
        Resource doc = new Resource.Document("acme", "_tenant", "notes.md");

        assertThat(service.check(user("adm"), doc, Action.WRITE)).isFalse();
        assertThat(service.check(user("root"), doc, Action.WRITE)).isTrue();
    }

    @Test
    void ownHub_isAdministeredByItsOwner() {
        PermissionService service = new FakePermissionProvider().service();

        assertThat(service.check(
                        user("alice"),
                        new Resource.Document("acme", "_user_alice", "_vance/hooks/x.yaml"),
                        Action.WRITE))
                .isTrue();
        assertThat(service.check(user("alice"), new Resource.Project("acme", "_user_alice"), Action.ADMIN))
                .isTrue();
    }

    @Test
    void serviceAccount_worksInItsHub_butCannotRewriteItsConfiguration() {
        PermissionService service = new FakePermissionProvider().service();
        SecurityContext agent = user("_trillian-x");

        assertThat(service.check(agent, new Resource.Document("acme", "_user__trillian-x", "notes.md"), Action.WRITE))
                .isTrue();
        assertThat(service.check(
                        agent,
                        new Resource.Document("acme", "_user__trillian-x", "_vance/exposure.yaml"),
                        Action.WRITE))
                .isFalse();
    }

    @Test
    void foreignHub_needsTenantAdmin_evenWithAMembership() {
        PermissionService service = new FakePermissionProvider()
                .member("_user_bob", "alice", ProjectRole.ADMIN)
                .tenant("adm", TenantRole.ADMIN)
                .service();
        Resource bobsHub = new Resource.Project("acme", "_user_bob");

        assertThat(service.check(user("alice"), bobsHub, Action.READ)).isFalse();
        assertThat(service.check(user("adm"), bobsHub, Action.WRITE)).isTrue();
    }

    @Test
    void configNamespace_needsProjectAdmin() {
        PermissionService service = new FakePermissionProvider()
                .member("proj", "worker", ProjectRole.MEMBER)
                .member("proj", "owner", ProjectRole.ADMIN)
                .service();
        Resource recipe = new Resource.Document("acme", "proj", "_vance/recipes/x.yaml");

        assertThat(service.check(user("worker"), recipe, Action.WRITE)).isFalse();
        assertThat(service.check(user("worker"), recipe, Action.READ)).isTrue();
        assertThat(service.check(user("owner"), recipe, Action.WRITE)).isTrue();
    }

    @Test
    void admin_cannotActOnAUserAboveThem() {
        PermissionService service = new FakePermissionProvider()
                .tenant("adm", TenantRole.ADMIN)
                .tenant("root", TenantRole.TENANT_ADMIN)
                .service();

        assertThat(service.check(user("adm"), new Resource.User("acme", "root"), Action.WRITE))
                .isFalse();
        assertThat(service.check(user("adm"), new Resource.User("acme", "bob"), Action.WRITE))
                .isTrue();
        assertThat(service.check(user("adm"), new Resource.User("acme", "root"), Action.READ))
                .isTrue();
        assertThat(service.check(user("adm"), new Resource.Setting("acme", "user", "root", "k"), Action.WRITE))
                .isFalse();
    }

    // ── role-shaped helpers ──

    @Test
    void mayActAs_onlyDownwardsOrSideways() {
        PermissionService service = new FakePermissionProvider()
                .tenant("adm", TenantRole.ADMIN)
                .tenant("adm2", TenantRole.ADMIN)
                .tenant("root", TenantRole.TENANT_ADMIN)
                .service();

        assertThat(service.mayActAs(user("adm"), "root")).isFalse();
        assertThat(service.mayActAs(user("adm"), "adm2")).isTrue();
        assertThat(service.mayActAs(user("root"), "adm")).isTrue();
        assertThat(service.mayActAs(user("bob"), "bob")).isTrue();
        assertThat(service.mayActAs(SecurityContext.SYSTEM, "root")).isTrue();
    }

    @Test
    void enforceMayActAs_throwsAsImpersonationOfTheTarget() {
        PermissionService service = new FakePermissionProvider()
                .tenant("root", TenantRole.TENANT_ADMIN)
                .service();

        assertThatThrownBy(() -> service.enforceMayActAs(user("bob"), "root"))
                .isInstanceOfSatisfying(PermissionDeniedException.class, ex -> {
                    assertThat(ex.getResource()).isEqualTo(new Resource.User("acme", "root"));
                    assertThat(ex.getAction()).isEqualTo(Action.IMPERSONATE);
                });
    }

    @Test
    void tenantRoleOf_failsClosedToUser() {
        PermissionService service = new FakePermissionProvider().failing().service();

        assertThat(service.tenantRoleOf(user("root"))).isEqualTo(TenantRole.USER);
        assertThat(service.tenantRoleOf(SecurityContext.SYSTEM)).isEqualTo(TenantRole.TENANT_ADMIN);
    }

    @Test
    void projectRoleOf_reportsTheHubOwnerRole() {
        PermissionService service = new FakePermissionProvider().service();

        assertThat(service.projectRoleOf(user("alice"), "_user_alice")).isEqualTo(ProjectRole.ADMIN);
        assertThat(service.projectRoleOf(user("_svc"), "_user__svc")).isEqualTo(ProjectRole.MEMBER);
        assertThat(service.projectRoleOf(user("alice"), "proj")).isNull();
    }
}
