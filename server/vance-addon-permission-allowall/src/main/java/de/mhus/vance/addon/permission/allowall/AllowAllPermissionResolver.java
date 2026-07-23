package de.mhus.vance.addon.permission.allowall;

import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionResolver;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Permits every check, logging it on DEBUG.
 *
 * <p>The DEBUG line doubles as the enforcement-wiring tracking signal — grep it
 * to see which call sites already run {@code PermissionService.enforce}. Used by
 * dev/test/open deployments; production replaces this addon with a real
 * provider ({@code vance-addon-simpleauth} or an enterprise governor).
 */
@Slf4j
public class AllowAllPermissionResolver implements PermissionResolver {

    @Override
    public boolean isAllowed(SecurityContext subject, Resource resource, Action action) {
        log.debug("ALLOW-ALL: subject={}:{} tenant={} action={} resource={}",
                subject.subjectType(), subject.subjectId(), subject.tenantId(),
                action, resource);
        return true;
    }
}
