package de.mhus.vance.shared.permission;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper provider: everybody is {@link TenantRole#TENANT_ADMIN} and
 * project {@link ProjectRole#ADMIN}, so the role table allows everything, and
 * the verdict is left to the test through the veto — {@link #denyNext} flips
 * the next decision, {@link #verdict} sets a permanent one. Every check that
 * reaches the veto is recorded.
 *
 * <p>The core preconditions still run first: a cross-tenant check is denied
 * without being recorded, exactly as it would be in production.
 *
 * <p>Serves as the single {@link PermissionProvider} in test contexts — register
 * it as {@code @Bean PermissionProvider}, which also makes the core's local
 * provider step aside — or build a standalone service with {@link #service()}.
 */
public class RecordingPermissionProvider implements PermissionProvider {

    public record Check(SecurityContext subject, Resource resource, Action action) {}

    private final List<Check> checks = new CopyOnWriteArrayList<>();
    private volatile boolean verdict = true;
    private volatile boolean denyNext = false;

    /** A {@link PermissionService} backed by this provider; nobody is in any team. */
    public PermissionService service() {
        return new PermissionService(List.of(this), (tenantId, username) -> List.of());
    }

    @Override
    public TenantRole tenantRole(String tenantId, String username) {
        return TenantRole.TENANT_ADMIN;
    }

    @Override
    public ProjectRole projectRole(SecurityContext subject, String tenantId, String project) {
        return ProjectRole.ADMIN;
    }

    @Override
    public boolean vetoes(SecurityContext subject, Resource resource, Action action) {
        checks.add(new Check(subject, resource, action));
        if (denyNext) {
            denyNext = false;
            return true;
        }
        return !verdict;
    }

    public List<Check> checks() {
        return List.copyOf(checks);
    }

    public Check lastCheck() {
        if (checks.isEmpty()) {
            throw new IllegalStateException("No checks recorded");
        }
        return checks.get(checks.size() - 1);
    }

    public void clear() {
        checks.clear();
    }

    public RecordingPermissionProvider verdict(boolean verdict) {
        this.verdict = verdict;
        return this;
    }

    public RecordingPermissionProvider denyNext() {
        this.denyNext = true;
        return this;
    }
}
