package de.mhus.vance.shared.permission;

import java.util.List;
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
 *
 * <p><b>Exactly one provider is mandatory.</b> The {@link PermissionResolver}
 * is contributed by a provider addon (allow-all for dev/test, simple-auth for
 * production, or an enterprise governor) — never by this module. The
 * constructor enforces that exactly one is on the classpath and fails startup
 * fast with a clear message otherwise, so a context can never boot silently
 * without authorization or with two ambiguous providers.
 */
@Service
@Slf4j
public class PermissionService {

    private final PermissionResolver resolver;

    public PermissionService(List<PermissionResolver> providers) {
        if (providers.size() != 1) {
            String detail = providers.isEmpty()
                    ? "none — load a permission-provider addon (e.g. vance-addon-permission-allowall "
                            + "for dev/test, vance-addon-simpleauth for production)"
                    : providers.stream().map(p -> p.getClass().getName()).toList()
                            + " — load exactly one";
            throw new IllegalStateException(
                    "Vance requires exactly one permission provider on the classpath; found "
                            + providers.size() + ": " + detail);
        }
        this.resolver = providers.get(0);
    }

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
