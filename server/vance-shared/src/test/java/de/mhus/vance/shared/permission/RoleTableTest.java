package de.mhus.vance.shared.permission;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Row by row: what each role permits (stage 4). */
class RoleTableTest {

    private static final SecurityContext ALICE = SecurityContext.user("alice", "acme", List.of("eng"));
    private static final TeamMembership BOB_IN_ENG =
            (tenantId, username) -> "bob".equals(username) ? List.of("eng") : List.of();

    private static boolean allows(Resource r, Action a, TenantRole t, @Nullable ProjectRole p) {
        return RoleTable.allows(ALICE, r, a, t, p, BOB_IN_ENG);
    }

    @Test
    void tenant_readAndStartForEveryone() {
        Resource tenant = new Resource.Tenant("acme");
        assertThat(allows(tenant, Action.READ, TenantRole.USER, null)).isTrue();
        assertThat(allows(tenant, Action.START, TenantRole.USER, null)).isTrue();
    }

    @Test
    void tenant_createNeedsCreator() {
        Resource tenant = new Resource.Tenant("acme");
        assertThat(allows(tenant, Action.CREATE, TenantRole.USER, null)).isFalse();
        assertThat(allows(tenant, Action.CREATE, TenantRole.CREATOR, null)).isTrue();
    }

    @Test
    void tenant_writeNeedsAdmin_configNeedsTenantAdmin() {
        Resource tenant = new Resource.Tenant("acme");
        assertThat(allows(tenant, Action.WRITE, TenantRole.CREATOR, null)).isFalse();
        assertThat(allows(tenant, Action.WRITE, TenantRole.ADMIN, null)).isTrue();
        assertThat(allows(tenant, Action.ADMIN, TenantRole.ADMIN, null)).isFalse();
        assertThat(allows(tenant, Action.ADMIN, TenantRole.TENANT_ADMIN, null)).isTrue();
    }

    @Test
    void project_followsMembership() {
        Resource doc = new Resource.Document("acme", "proj", "a.md");
        assertThat(allows(doc, Action.READ, TenantRole.USER, null)).isFalse();
        assertThat(allows(doc, Action.READ, TenantRole.USER, ProjectRole.READER))
                .isTrue();
        assertThat(allows(doc, Action.WRITE, TenantRole.USER, ProjectRole.READER))
                .isFalse();
        assertThat(allows(doc, Action.WRITE, TenantRole.USER, ProjectRole.MEMBER))
                .isTrue();
        assertThat(allows(doc, Action.ADMIN, TenantRole.USER, ProjectRole.MEMBER))
                .isFalse();
        assertThat(allows(doc, Action.ADMIN, TenantRole.USER, ProjectRole.ADMIN))
                .isTrue();
    }

    @Test
    void project_tenantAdminRoleCoversEveryProjectWithoutMembership() {
        Resource session = new Resource.Session("acme", "proj", "s1");
        assertThat(allows(session, Action.READ, TenantRole.ADMIN, null)).isTrue();
        assertThat(allows(session, Action.ADMIN, TenantRole.ADMIN, null)).isTrue();
        assertThat(allows(session, Action.READ, TenantRole.CREATOR, null)).isFalse();
    }

    @Test
    void tenantProject_isReadableByEveryone() {
        assertThat(allows(new Resource.Project("acme", "_tenant"), Action.READ, TenantRole.USER, null))
                .isTrue();
    }

    @Test
    void settings_byScope() {
        Resource own = new Resource.Setting("acme", "user", "alice", "k");
        Resource others = new Resource.Setting("acme", "user", "bob", "k");
        Resource tenant = new Resource.Setting("acme", "tenant", "acme", "k");
        Resource project = new Resource.Setting("acme", "project", "proj", "k");

        assertThat(allows(own, Action.WRITE, TenantRole.USER, null)).isTrue();
        assertThat(allows(others, Action.READ, TenantRole.USER, null)).isFalse();
        assertThat(allows(others, Action.WRITE, TenantRole.ADMIN, null)).isTrue();
        assertThat(allows(tenant, Action.READ, TenantRole.USER, null)).isTrue();
        assertThat(allows(tenant, Action.WRITE, TenantRole.ADMIN, null)).isFalse();
        assertThat(allows(tenant, Action.WRITE, TenantRole.TENANT_ADMIN, null)).isTrue();
        assertThat(allows(project, Action.WRITE, TenantRole.USER, ProjectRole.MEMBER))
                .isTrue();
    }

    @Test
    void usersAndTeams_readableByAll_managedByAdmin() {
        Resource user = new Resource.User("acme", "bob");
        Resource team = new Resource.Team("acme", "eng");
        assertThat(allows(user, Action.READ, TenantRole.USER, null)).isTrue();
        assertThat(allows(user, Action.WRITE, TenantRole.CREATOR, null)).isFalse();
        assertThat(allows(team, Action.ADMIN, TenantRole.ADMIN, null)).isTrue();
    }

    @Test
    void inbox_assigneeOrTeammate() {
        assertThat(allows(new Resource.InboxItem("acme", "i1", "alice"), Action.WRITE, TenantRole.USER, null))
                .isTrue();
        assertThat(allows(new Resource.InboxItem("acme", "i2", "bob"), Action.READ, TenantRole.USER, null))
                .isTrue();
        assertThat(allows(new Resource.InboxItem("acme", "i3", "carol"), Action.READ, TenantRole.TENANT_ADMIN, null))
                .isFalse();
        assertThat(allows(new Resource.InboxItem("acme", "i4", ""), Action.READ, TenantRole.TENANT_ADMIN, null))
                .isFalse();
    }
}
