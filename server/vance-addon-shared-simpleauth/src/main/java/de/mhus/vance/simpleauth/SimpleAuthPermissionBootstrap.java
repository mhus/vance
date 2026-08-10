package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.permission.PermissionBootstrap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Simple-Auth implementation of the core {@link PermissionBootstrap} SPI:
 * translates the intent methods to {@link GrantRole} and writes through
 * {@link PermissionGrantService}. Present only when this addon is loaded, so
 * {@code BootstrapBrainService} / {@code ProjectLifecycleService} / the anus
 * setup wizard seed initial rights via {@code ObjectProvider.ifAvailable}.
 * Idempotent — {@code set} overwrites, never duplicates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimpleAuthPermissionBootstrap implements PermissionBootstrap {

    private static final String CREATED_BY = "bootstrap";

    private final PermissionGrantService grants;
    private final PermissionRequestService requests;

    @Override
    public void grantTenantAdmin(String tenant, String username) {
        grants.set(tenant, GrantScopeType.TENANT, tenant,
                GrantSubjectType.USER, username, GrantRole.ADMIN, CREATED_BY);
    }

    @Override
    public void grantProjectAdmin(String tenant, String project, String username) {
        grants.set(tenant, GrantScopeType.PROJECT, project,
                GrantSubjectType.USER, username, GrantRole.ADMIN, CREATED_BY);
    }

    @Override
    public void grantProjectTeamWriter(String tenant, String project, String team) {
        grants.set(tenant, GrantScopeType.PROJECT, project,
                GrantSubjectType.TEAM, team, GrantRole.WRITER, CREATED_BY);
    }

    @Override
    public void revokeAll(String tenant, String username) {
        int removed = 0;
        for (PermissionGrantDocument grant :
                grants.forSubject(tenant, GrantSubjectType.USER, username)) {
            if (grants.remove(tenant, grant.getScopeType(), grant.getScopeId(),
                    GrantSubjectType.USER, username)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Revoked {} grant(s) of user '{}' in tenant '{}'", removed, username, tenant);
        }
        // A request naming a user who no longer exists must not survive:
        // approving it later could hit a different account that reused the
        // name. Short-lived service accounts make that a real sequence,
        // not a theoretical one.
        requests.expireForSubject(tenant, GrantSubjectType.USER, username);
    }
}
