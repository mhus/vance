package de.mhus.vance.shared.permission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The single entry point for permission checks across the server.
 *
 * <p>Inbound layers call {@link #enforce} before delegating to a service.
 * Internal callers either pass a real {@link SecurityContext} they were given,
 * or {@link SecurityContext#SYSTEM} when running outside any user request.
 *
 * <p>This service deliberately does no rule evaluation itself — it logs the
 * check at TRACE and forwards to the configured {@link PermissionResolver}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

    private final PermissionResolver resolver;

    public boolean check(SecurityContext subject, Resource resource, Action action) {
        boolean allowed = resolver.isAllowed(subject, resource, action);
        if (log.isTraceEnabled()) {
            log.trace("permission {}: subject={}:{} tenant={} action={} resource={}",
                    allowed ? "ALLOW" : "DENY",
                    subject.subjectType(), subject.subjectId(), subject.tenantId(),
                    action, resource);
        }
        return allowed;
    }

    public void enforce(SecurityContext subject, Resource resource, Action action) {
        if (!check(subject, resource, action)) {
            throw new PermissionDeniedException(subject, resource, action);
        }
    }
}
