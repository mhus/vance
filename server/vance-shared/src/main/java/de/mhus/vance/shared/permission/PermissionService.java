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
                    ? "none — load a permission-provider addon "
                            + "(vance-addon-shared-simpleauth, or an enterprise governor)"
                    : providers.stream().map(p -> p.getClass().getName()).toList()
                            + " — load exactly one";
            throw new IllegalStateException(
                    "Vance requires exactly one permission provider on the classpath; found "
                            + providers.size() + ": " + detail);
        }
        this.resolver = providers.get(0);
    }

    public boolean check(SecurityContext subject, Resource resource, Action action) {
        return check(subject, resource, action, WriteReason.USER);
    }

    /**
     * Reason-aware check — {@code reason} lets the resolver allow a trusted
     * internal write ({@link WriteReason#SYSTEM}) while {@code subject} still
     * carries the real actor. Only server code passes anything but
     * {@link WriteReason#USER}.
     */
    public boolean check(
            SecurityContext subject, Resource resource, Action action, WriteReason reason) {
        boolean allowed = resolver.isAllowed(subject, resource, action, reason);
        if (log.isTraceEnabled()) {
            log.trace("permission {}: subject={}:{} tenant={} action={} reason={} resource={}",
                    allowed ? "ALLOW" : "DENY",
                    subject.subjectType(), subject.subjectId(), subject.tenantId(),
                    action, reason, resource);
        }
        return allowed;
    }

    public void enforce(SecurityContext subject, Resource resource, Action action) {
        enforce(subject, resource, action, WriteReason.USER);
    }

    public void enforce(
            SecurityContext subject, Resource resource, Action action, WriteReason reason) {
        if (!check(subject, resource, action, reason)) {
            throw new PermissionDeniedException(subject, resource, action);
        }
    }
}
