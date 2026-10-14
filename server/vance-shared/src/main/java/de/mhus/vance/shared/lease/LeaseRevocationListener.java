package de.mhus.vance.shared.lease;

import java.util.List;

/**
 * Receives holders that {@link ResourceLeaseService} reaped on its own —
 * the lazy idle sweep at {@code acquire}. Such a reap frees the slot but
 * leaves the resource running until the lease type's {@link LeaseHandler}
 * reacts, so the reaped holders must reach the same dispatch as the
 * scheduled sweep's.
 *
 * <p>The listener lives in vance-brain (handler registry, pod routing);
 * contexts without one (anus) log the reap and move on. The call sits on
 * the acquiring thread: implementations must not block it — dispatch
 * asynchronously.
 */
public interface LeaseRevocationListener {

    /** Holders deleted by the service; their counters are already booked back. */
    void onReaped(List<LeaseHolderInfo> holders);
}
