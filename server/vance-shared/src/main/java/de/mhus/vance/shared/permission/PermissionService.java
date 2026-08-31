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
        // Credential attenuation — evaluated FIRST, ahead of the system-trust
        // boundary below. A confined credential (an INTEGRATION token pinned to
        // one project) must not reach outside its project even along a path
        // where server code vouches for the write with WriteReason.SYSTEM: that
        // reason says "this write is legitimate policy-wise", not "this caller
        // may touch another project". Scope and policy are different questions
        // and the scope one is answered first.
        if (!withinCredentialScope(subject, resource)) {
            log.debug("permission DENY (out of credential scope): subject={} restrictedTo={} "
                            + "action={} resource={}",
                    subject.subjectId(), subject.restrictedToProject(), action, resource);
            return false;
        }
        // Framework trust boundary — enforced here, before the provider, so the
        // provider only ever evaluates genuine user policy and no provider can
        // accidentally break internal server operations. A SYSTEM subject (the
        // server acting as itself) or a SYSTEM reason (server code vouching for
        // a write; only Java can set it — never a user surface) is trusted; the
        // real actor stays in `subject` for audit. WriteReason therefore never
        // reaches the resolver (see PermissionResolver SPI).
        if (subject.subjectType() == SubjectType.SYSTEM || reason == WriteReason.SYSTEM) {
            if (log.isTraceEnabled()) {
                log.trace("permission ALLOW (system-trust): subject={}:{} tenant={} "
                                + "action={} reason={} resource={}",
                        subject.subjectType(), subject.subjectId(), subject.tenantId(),
                        action, reason, resource);
            }
            return true;
        }
        boolean allowed = resolver.isAllowed(subject, resource, action);
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

    /**
     * Whether {@code resource} lies inside the project a confined credential is
     * pinned to. Always true for the normal, unconfined context.
     *
     * <p><b>Fail-closed on anything without a project.</b> A resource that names
     * no project — the tenant itself, a user, a team, a tenant-scoped setting,
     * an inbox item — is not "outside the restriction by accident", it is
     * exactly the kind of reach a project-confined credential must not have. The
     * alternative (allow what we cannot classify) would mean every new
     * {@link Resource} kind silently widens every token already in the field.
     *
     * <p>The switch is exhaustive over the sealed {@link Resource} on purpose:
     * adding a kind stops compiling here, which is the moment to decide whether
     * it carries a project.
     */
    private static boolean withinCredentialScope(SecurityContext subject, Resource resource) {
        String confinedTo = subject.restrictedToProject();
        if (confinedTo == null) {
            return true;
        }
        return switch (resource) {
            case Resource.Project r -> confinedTo.equals(r.projectName());
            case Resource.Document r -> confinedTo.equals(r.projectName());
            case Resource.Session r -> confinedTo.equals(r.projectName());
            case Resource.ThinkProcess r -> confinedTo.equals(r.projectName());
            case Resource.Setting r ->
                    "project".equals(r.referenceType()) && confinedTo.equals(r.referenceId());
            case Resource.Tenant ignored -> false;
            case Resource.Team ignored -> false;
            case Resource.User ignored -> false;
            case Resource.InboxItem ignored -> false;
        };
    }
}
