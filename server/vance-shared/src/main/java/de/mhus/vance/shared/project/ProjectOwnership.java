package de.mhus.vance.shared.project;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The one place that answers "who owns this project right now".
 *
 * <p>Ownership is a <b>lease</b>, not a stored fact: {@code homePodId} names
 * the holder and {@code claimedAt} says when it last renewed. A lease that
 * stopped being renewed is expired, and an expired lease means <em>nobody</em>
 * owns the project — whatever the fields still say. That is what makes a
 * crashed pod harmless without anyone cleaning up after it: nothing durable
 * claims to be true, so nothing has to be un-claimed on the way out (which
 * could not work anyway — {@code kill -9}, OOM and pod eviction run no
 * shutdown hook).
 *
 * <p><b>Hard rule, in the spirit of the {@code SettingType} predicates:</b>
 * {@code getHomePodId()} and {@code getClaimedAt()} are read here and in
 * {@code ProjectService}'s claim path — <em>nowhere else</em>. Every other
 * caller asks this class. A hand-rolled comparison somewhere in the tree is
 * how the twelve divergent readings of {@code homeNode} came about, and each
 * of them was correct-looking in isolation (see
 * {@code planning/project-ownership-lease-design.md} §6).
 *
 * <p>Pure computation, no I/O: the caller already holds the document, so
 * answering costs nothing. This is deliberately different from the previous
 * design, where every routing decision had to join against
 * {@code brain_pods} to find out whether a node name still meant anything.
 */
public final class ProjectOwnership {

    private ProjectOwnership() {}

    /**
     * The pod id currently holding a valid lease, or empty when nobody does —
     * unclaimed, or claimed by a holder that stopped renewing.
     *
     * <p>Podless projects (see {@link ProjectService#isPodless}) always answer
     * empty: they are never pinned to a pod and live wherever the WS lands.
     * Callers must not read that as "the owner died".
     */
    public static Optional<String> liveOwnerPodId(
            ProjectDocument project, Instant now, Duration leaseTtl) {
        String holder = project.getHomePodId();
        if (holder == null || holder.isBlank()) return Optional.empty();
        if (isExpired(project.getClaimedAt(), now, leaseTtl)) return Optional.empty();
        return Optional.of(holder);
    }

    /** True when {@code podId} holds a valid lease on the project. */
    public static boolean isOwnedBy(
            ProjectDocument project, String podId, Instant now, Duration leaseTtl) {
        return liveOwnerPodId(project, now, leaseTtl)
                .filter(holder -> holder.equals(podId))
                .isPresent();
    }

    /**
     * True when nobody holds a valid lease — the project is free to be taken
     * over. Same question as {@link #liveOwnerPodId} being empty, named for
     * the call sites that only care whether they may adopt.
     */
    public static boolean isUnowned(ProjectDocument project, Instant now, Duration leaseTtl) {
        return liveOwnerPodId(project, now, leaseTtl).isEmpty();
    }

    /**
     * Whether a renewal timestamp has aged out of the lease window.
     *
     * <p>Two edge cases, both decided towards "do not strand a project":
     * <ul>
     *   <li>{@code null} counts as expired. A holder without a renewal
     *       timestamp cannot be validated, and refusing to validate it would
     *       leave the project owned by something unprovable forever.</li>
     *   <li>A timestamp in the future counts as valid. Clocks disagree; the
     *       holder is clearly renewing, and stealing a project because our own
     *       clock runs behind would be the worse failure.</li>
     * </ul>
     */
    public static boolean isExpired(
            @Nullable Instant claimedAt, Instant now, Duration leaseTtl) {
        if (claimedAt == null) return true;
        return claimedAt.isBefore(now.minus(leaseTtl));
    }
}
