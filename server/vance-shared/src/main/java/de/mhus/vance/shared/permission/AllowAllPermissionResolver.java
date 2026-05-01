package de.mhus.vance.shared.permission;

import lombok.extern.slf4j.Slf4j;

/**
 * Default resolver: permits everything, logs each check on DEBUG.
 *
 * <p>Goal of phase 1 is to wire {@link PermissionService#enforce} calls into
 * every inbound layer without yet blocking anyone. The DEBUG log line is the
 * tracking signal — grep it to see which call sites already check, and which
 * are still missing.
 *
 * <p>Registered as a fallback bean in {@link PermissionConfiguration} — it
 * disappears as soon as a downstream application provides its own
 * {@link PermissionResolver} bean (e.g. a role-based one).
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
