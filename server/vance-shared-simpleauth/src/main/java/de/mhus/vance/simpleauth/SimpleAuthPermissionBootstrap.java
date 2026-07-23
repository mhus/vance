package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.permission.PermissionBootstrap;
import lombok.RequiredArgsConstructor;
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
public class SimpleAuthPermissionBootstrap implements PermissionBootstrap {

    private static final String CREATED_BY = "bootstrap";

    private final PermissionGrantService grants;

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
}
