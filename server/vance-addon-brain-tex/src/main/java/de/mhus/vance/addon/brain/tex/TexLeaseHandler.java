package de.mhus.vance.addon.brain.tex;

import de.mhus.vance.shared.lease.LeaseHandler;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lease handler for {@code tex} compiles: a revoked lease aborts the
 * poll loop of the affected job ({@link TexCompileTracker}). The lease
 * is acquired {@code podBound} — the poll state lives on the holder pod,
 * so this dispatch only matters while that pod is alive (local or via
 * the internal revoke POST); a dead pod has no poll loop left.
 *
 * <p>The remote compile job cannot be cancelled (the rbehzadan service
 * has no cancel endpoint) and runs out server-side — accepted for the
 * short job profile of a LaTeX compile.
 */
@Component
@RequiredArgsConstructor
public class TexLeaseHandler implements LeaseHandler {

    public static final String TYPE = "tex";

    private final TexCompileTracker tracker;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void onRevoked(LeaseHolderInfo info) {
        Object jobId = info.payload().get("jobId");
        if (jobId != null) {
            tracker.cancel(String.valueOf(jobId));
        }
    }
}
