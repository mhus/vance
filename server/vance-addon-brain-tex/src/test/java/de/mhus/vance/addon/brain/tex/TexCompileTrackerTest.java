package de.mhus.vance.addon.brain.tex;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.lease.LeaseHolderInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The abort registry behind {@link TexLeaseHandler}: a revocation marks
 * only a running poll loop, and one that arrives after the loop ended
 * leaves no entry behind.
 */
class TexCompileTrackerTest {

    private final TexCompileTracker tracker = new TexCompileTracker();
    private final TexLeaseHandler handler = new TexLeaseHandler(tracker);

    private static LeaseHolderInfo revoked(String jobId) {
        return new LeaseHolderInfo(
                "rl-1",
                TexLeaseHandler.TYPE,
                "tex2pdf",
                "tex-compile",
                "acme",
                "proj",
                null,
                "pod-1",
                true,
                Map.of("jobId", jobId));
    }

    @Test
    void revocation_marksTheRunningJob() {
        tracker.begin("job-1");

        handler.onRevoked(revoked("job-1"));

        assertThat(tracker.isCancelled("job-1")).isTrue();
        tracker.end("job-1");
        assertThat(tracker.size()).isZero();
    }

    @Test
    void lateRevocation_afterTheLoopEnded_leavesNothingBehind() {
        tracker.begin("job-1");
        tracker.end("job-1");

        handler.onRevoked(revoked("job-1"));

        assertThat(tracker.size()).isZero();
        assertThat(tracker.isCancelled("job-1")).isFalse();
    }

    @Test
    void revocationWithoutJobId_isIgnored() {
        handler.onRevoked(new LeaseHolderInfo(
                "rl-1", TexLeaseHandler.TYPE, "tex2pdf", "tex-compile", "acme", "proj", null, "pod-1", true, Map.of()));

        assertThat(tracker.size()).isZero();
    }
}
