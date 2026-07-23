package de.mhus.vance.shared.permission;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper resolver that records every check it sees and lets the test
 * decide whether to allow or deny. Default verdict is {@code true} (allow);
 * call {@link #denyNext} to flip the next decision to {@code false}, or
 * {@link #verdict} to set a permanent verdict for all subsequent checks.
 *
 * <p>Serves as the single {@link PermissionResolver} in test contexts that
 * boot Spring without a provider addon — register it as
 * {@code @Bean PermissionResolver} so the mandatory-provider guard in
 * {@link PermissionService} is satisfied.
 */
public class RecordingPermissionResolver implements PermissionResolver {

    public record Check(SecurityContext subject, Resource resource, Action action) {}

    private final List<Check> checks = new CopyOnWriteArrayList<>();
    private volatile boolean verdict = true;
    private volatile boolean denyNext = false;

    @Override
    public boolean isAllowed(SecurityContext subject, Resource resource, Action action) {
        checks.add(new Check(subject, resource, action));
        if (denyNext) {
            denyNext = false;
            return false;
        }
        return verdict;
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

    public RecordingPermissionResolver verdict(boolean verdict) {
        this.verdict = verdict;
        return this;
    }

    public RecordingPermissionResolver denyNext() {
        this.denyNext = true;
        return this;
    }
}
