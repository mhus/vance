package de.mhus.vance.brain.project;

import de.mhus.vance.shared.project.ProjectService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What <em>this</em> JVM has actually brought up: the pod-local half of
 * project ownership.
 *
 * <p>Three lifetimes are involved in "is this project live", and conflating
 * any two of them is what kept breaking
 * ({@code planning/project-ownership-lease-design.md} §2):
 *
 * <ul>
 *   <li><b>Intent</b> — {@code ProjectDocument.status}: should it be live at
 *       all. Persistent.</li>
 *   <li><b>Ownership</b> — the lease: which pod holds it right now. Persistent
 *       but self-expiring.</li>
 *   <li><b>Activation</b> — this registry: what did <em>this process</em>
 *       actually start. Lives exactly as long as the JVM, because that is how
 *       long the in-memory schedulers, hooks and tool scopes live.</li>
 * </ul>
 *
 * <p>Activation cannot be read from Mongo, and trying was the bug:
 * {@code bring} used to short-circuit on {@code status == RUNNING}, which a
 * <em>different, now dead</em> pod had written. The new owner then owned the
 * project without ever starting anything for it — no workspace, and no
 * {@link ProjectEnginesStartRequested}, so scheduler, hooks, tool preload and
 * kit provisioning stayed dark. "Already RUNNING" is not "already running
 * here", and only this pod can answer the second question.
 *
 * <p>Deliberately not persisted and deliberately not reconstructed at boot: a
 * fresh JVM has started nothing, and that is the truth we want it to tell.
 */
@Component
@Slf4j
public class ProjectActivationRegistry {

    /** {@code tenantId + '/' + projectName} — the key used everywhere else too. */
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public static String key(String tenantId, String projectName) {
        return tenantId + "/" + projectName;
    }

    /** Records that this pod has brought the project up. Idempotent. */
    public void activate(String tenantId, String projectName) {
        active.add(key(tenantId, projectName));
    }

    /** Forgets the project. Idempotent; returns whether it had been active. */
    public boolean deactivate(String tenantId, String projectName) {
        return active.remove(key(tenantId, projectName));
    }

    public boolean isActive(String tenantId, String projectName) {
        return active.contains(key(tenantId, projectName));
    }

    /** Snapshot of {@code tenant/project} keys, safe to iterate. */
    public Set<String> snapshot() {
        return Set.copyOf(active);
    }

    /** How many projects this pod has activated, podless ones included. */
    public int size() {
        return active.size();
    }

    /**
     * How many of the activated projects are supposed to hold a lease — the
     * number {@code ProjectLeaseService} compares its renewal count against.
     *
     * <p>Podless projects ({@code _user_<login>}, {@code _tenant}) are
     * activated here on purpose — their schedulers and hooks do live on this
     * pod — but they take no lease by definition. Counting them made the
     * comparison permanently short: one connected user was enough to log
     * "a lease was taken away" every single minute and pay for the
     * reconciliation query behind it, which then correctly skipped exactly the
     * projects that had caused the alarm. A warning that fires in the normal
     * case cannot report an anomaly, so the filter belongs one level earlier,
     * in the count itself.
     */
    public int leaseHoldingSize() {
        int count = 0;
        for (String key : active) {
            int slash = key.indexOf('/');
            if (slash <= 0) continue;
            if (ProjectService.isPodless(key.substring(slash + 1))) continue;
            count++;
        }
        return count;
    }
}
