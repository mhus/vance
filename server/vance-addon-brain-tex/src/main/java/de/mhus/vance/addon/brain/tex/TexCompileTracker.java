package de.mhus.vance.addon.brain.tex;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory abort registry for running tex2pdf poll loops, keyed by the
 * remote job id. The {@link TexLeaseHandler} marks a job cancelled when
 * its lease was revoked (idle/pod reap); the
 * {@link Tex2PdfRbehzadanExecutor} poll loop observes the mark and stops
 * polling instead of running to its deadline.
 *
 * <p>Pod-local by design: the {@code tex} lease is {@code podBound}, so
 * a revocation for a dead pod has no poll loop left to abort — the mark
 * would land on a registry that no longer matters. The remote job itself
 * cannot be cancelled (the tex2pdf service has no cancel endpoint) and
 * expires server-side.
 */
@Component
public class TexCompileTracker {

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /** Marks a job as cancelled — the poll loop stops at its next iteration. */
    public void cancel(String jobId) {
        if (jobId != null && !jobId.isBlank()) cancelled.add(jobId);
    }

    /** Whether the poll loop for {@code jobId} should stop. */
    public boolean isCancelled(String jobId) {
        return jobId != null && cancelled.contains(jobId);
    }

    /** Clears the mark once the poll loop has observed it (or finished normally). */
    public void forget(String jobId) {
        if (jobId != null) cancelled.remove(jobId);
    }
}
