package de.mhus.vance.addon.brain.tex;

import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * In-memory abort registry for running tex2pdf poll loops, keyed by the
 * remote job id. The poll loop registers its job ({@link #begin}) and
 * deregisters it when it ends ({@link #end}); the {@link TexLeaseHandler}
 * marks a <b>running</b> job cancelled when its lease was revoked
 * (idle/pod reap), and the {@link Tex2PdfRbehzadanExecutor} poll loop
 * observes the mark and stops polling instead of running to its deadline.
 * A revocation that arrives after the loop ended finds no entry and
 * leaves nothing behind.
 *
 * <p>Pod-local by design: the {@code tex} lease is {@code podBound}, so
 * a revocation for a dead pod has no poll loop left to abort. The remote
 * job itself cannot be cancelled (the tex2pdf service has no cancel
 * endpoint) and expires server-side.
 */
@Component
public class TexCompileTracker {

    /** Running jobs → cancelled flag. */
    private final ConcurrentHashMap<String, Boolean> running = new ConcurrentHashMap<>();

    /** Registers a running poll loop. */
    public void begin(String jobId) {
        running.put(jobId, Boolean.FALSE);
    }

    /** Marks a running job as cancelled — a no-op once its poll loop has ended. */
    public void cancel(@Nullable String jobId) {
        if (jobId == null || jobId.isBlank()) return;
        running.computeIfPresent(jobId, (id, cancelled) -> Boolean.TRUE);
    }

    /** Whether the poll loop for {@code jobId} should stop. */
    public boolean isCancelled(String jobId) {
        return Boolean.TRUE.equals(running.get(jobId));
    }

    /** Deregisters the poll loop — called when it ends, however it ends. */
    public void end(String jobId) {
        running.remove(jobId);
    }

    /** Number of registered poll loops — diagnostics and tests. */
    int size() {
        return running.size();
    }
}
