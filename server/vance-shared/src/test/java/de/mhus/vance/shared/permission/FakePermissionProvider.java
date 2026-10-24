package de.mhus.vance.shared.permission;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;

/** Test provider with roles set per user / per (project, user). Unknown users are USER, non-members. */
final class FakePermissionProvider implements PermissionProvider {

    private final Map<String, TenantRole> tenantRoles = new HashMap<>();
    private final Map<String, ProjectRole> projectRoles = new HashMap<>();
    private BiPredicate<Resource, Action> veto = (r, a) -> false;
    private boolean failing;

    FakePermissionProvider tenant(String user, TenantRole role) {
        tenantRoles.put(user, role);
        return this;
    }

    FakePermissionProvider member(String project, String user, ProjectRole role) {
        projectRoles.put(project + '\0' + user, role);
        return this;
    }

    FakePermissionProvider veto(BiPredicate<Resource, Action> veto) {
        this.veto = veto;
        return this;
    }

    FakePermissionProvider failing() {
        this.failing = true;
        return this;
    }

    PermissionService service() {
        return new PermissionService(java.util.List.of(this), (tenantId, username) -> java.util.List.of());
    }

    @Override
    public TenantRole tenantRole(String tenantId, String username) {
        if (failing) {
            throw new IllegalStateException("governor unreachable");
        }
        return tenantRoles.getOrDefault(username, TenantRole.USER);
    }

    @Override
    public @Nullable ProjectRole projectRole(SecurityContext subject, String tenantId, String project) {
        if (failing) {
            throw new IllegalStateException("governor unreachable");
        }
        return projectRoles.get(project + '\0' + subject.subjectId());
    }

    @Override
    public boolean vetoes(SecurityContext subject, Resource resource, Action action) {
        return veto.test(resource, action);
    }
}
