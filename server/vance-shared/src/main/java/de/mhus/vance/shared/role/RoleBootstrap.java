package de.mhus.vance.shared.role;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.role.request.RoleRequestService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Seeds <em>initial</em> roles from trusted code. It breaks the chicken-and-egg
 * of an enforcing setup: role administration needs someone who already holds
 * ADMIN, so the very first roles must be planted from a bootstrap path — the
 * Brain's tenant bootstrap, project creation, the Trillian setup and the anus
 * setup wizard.
 *
 * <p>Writes straight through {@link RoleAssignmentService}, without the
 * assignment rules of {@link RoleAdministrationService}: the callers are server
 * code acting on facts (this user created that project), not on a request.
 * Every method is idempotent — re-running a bootstrap on an existing database
 * must not fail or duplicate anything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleBootstrap {

    /** {@code createdBy} of every seeded assignment — lets a re-seed tell its own rows from deliberate ones. */
    public static final String CREATED_BY = "bootstrap";

    private final RoleAssignmentService assignments;
    private final RoleRequestService requests;

    /** Makes {@code username} TENANT_ADMIN of {@code tenant}. */
    public void grantTenantAdmin(String tenant, String username) {
        assignments.setTenantRole(tenant, username, TenantRole.TENANT_ADMIN, CREATED_BY);
    }

    /** Makes {@code username} ADMIN of {@code project} — typically its creator. */
    public void grantProjectAdmin(String tenant, String project, String username) {
        assignments.setProjectRole(tenant, project, RoleSubjectType.USER, username, ProjectRole.ADMIN, CREATED_BY);
    }

    /**
     * Makes {@code username} a MEMBER of {@code project} — an agent account that
     * works there on someone's behalf (a Trillian in its human's project) and
     * must not administer it: no memberships, no {@code _vance/} configuration.
     *
     * <p>Re-seeding never overrides a role someone set on purpose — raised to
     * ADMIN or lowered to READER, it is theirs to change, not a seed to
     * overwrite.
     */
    public void grantProjectMember(String tenant, String project, String username) {
        Optional<RoleAssignmentDocument> existing =
                assignments.find(tenant, RoleScopeType.PROJECT, project, RoleSubjectType.USER, username);
        if (existing.isPresent() && !CREATED_BY.equals(existing.get().getCreatedBy())) {
            log.info(
                    "Keeping deliberate {} membership of '{}' in project '{}' of tenant '{}'",
                    existing.get().getProjectRole(),
                    username,
                    project,
                    tenant);
            return;
        }
        assignments.setProjectRole(tenant, project, RoleSubjectType.USER, username, ProjectRole.MEMBER, CREATED_BY);
    }

    /**
     * Makes a {@code team} a MEMBER of {@code project} — the seed that turns the
     * organisational "this team works on this project" association into an
     * actual membership.
     */
    public void grantProjectTeamMember(String tenant, String project, String team) {
        assignments.setProjectRole(tenant, project, RoleSubjectType.TEAM, team, ProjectRole.MEMBER, CREATED_BY);
    }

    /**
     * Drops every role held by {@code username} in {@code tenant}, at any scope,
     * and expires every pending request naming it.
     *
     * <p>A role is keyed on the user <em>name</em>, not on the Mongo id, so it
     * survives the document unless somebody removes it — and a name can come
     * back (service accounts follow a scheme, human logins get reused), at which
     * point the new account silently inherits authority nobody granted it. A
     * pending request is the same hazard one approval later.
     *
     * <p>Idempotent: an unknown or already-cleaned user is a no-op. Every caller
     * relies on that.
     */
    public void revokeAll(String tenant, String username) {
        int removed = assignments.removeAll(tenant, RoleSubjectType.USER, username);
        if (removed > 0) {
            log.info("Revoked {} role assignment(s) of user '{}' in tenant '{}'", removed, username, tenant);
        }
        requests.expireForSubject(tenant, RoleSubjectType.USER, username);
    }
}
