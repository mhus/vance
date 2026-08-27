package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.anus.cluster.PodClusterService.PingResult;
import de.mhus.vance.anus.cluster.PodClusterService.PodPing;
import de.mhus.vance.anus.cluster.PodClusterService.PruneCandidate;
import de.mhus.vance.anus.cluster.PodClusterService.PruneReason;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The three cells that are now built from an enum instead of inline.
 *
 * <p>This test exists because of what the extraction did <em>not</em> have.
 * When the project operations moved out of {@code ProjectCommands}, thirteen
 * existing tests asserted on the command's text and proved the move changed
 * nothing. The pod commands had no such tests, and the move rewrote three
 * strings — so the expected values below are the wording from before the
 * extraction, copied from the old inline code, not from the new one.
 *
 * <p>It already caught one: the prune row used to print the <em>truncated</em>
 * responding podId with an ellipsis, because it reused the ping detail. The
 * first version of the extraction printed the raw id.
 */
class ClusterCommandsRenderingTest {

    private static final BrainPodDocument POD = BrainPodDocument.builder()
            .clusterId("default").podId("p-1").nodeName("pod-a").endpoint("10.0.0.7:9990")
            .build();

    private static PodPing ping(PingResult result, int status, String detail, String node) {
        return new PodPing(POD, result, status, Duration.ofMillis(12), detail, node);
    }

    // ─── RESULT cell ────────────────────────────────────────────────────────

    @Test
    void resultCell_keepsTheOldLabels() {
        assertThat(ClusterCommands.pingResultText(ping(PingResult.OK, 200, "pod-a", "pod-a")))
                .isEqualTo("OK");
        assertThat(ClusterCommands.pingResultText(ping(PingResult.STALE, 200, "other", "pod-b")))
                .isEqualTo("STALE");
        assertThat(ClusterCommands.pingResultText(
                ping(PingResult.UNREACHABLE, 0, "connect timed out", null)))
                .as("the label an operator learned is ERROR, not the enum's name")
                .isEqualTo("ERROR");
        assertThat(ClusterCommands.pingResultText(
                ping(PingResult.SKIPPED, 0, "no endpoint advertised", null)))
                .isEqualTo("SKIP");
    }

    @Test
    void resultCell_anHttpErrorCarriesItsCode() {
        assertThat(ClusterCommands.pingResultText(ping(PingResult.HTTP_ERROR, 503, "boom", null)))
                .isEqualTo("HTTP 503");
    }

    // ─── DETAIL cell ────────────────────────────────────────────────────────

    @Test
    void detailCell_okNamesTheServingPod() {
        assertThat(ClusterCommands.pingDetailText(ping(PingResult.OK, 200, "pod-a", "pod-a")))
                .isEqualTo("served by pod-a");
    }

    @Test
    void detailCell_staleTruncatesThePodId_ellipsisTwiceAndAll() {
        // The exact old shape, blemish included: truncate() appends an ellipsis
        // of its own and the cell appends a second one, so a long podId ends in
        // "……". Reproduced rather than tidied — this test's job is to prove the
        // extraction changed nothing, and the double ellipsis is what an
        // operator has been seeing. Fixing it is a separate, deliberate edit.
        assertThat(ClusterCommands.pingDetailText(
                ping(PingResult.STALE, 200, "0123456789abcdef", "pod-b")))
                .isEqualTo("answered by 'pod-b' (podId=0123456……)");
    }

    @Test
    void detailCell_shortPodIdStillGetsTheEllipsis() {
        // truncate() leaves anything at or below the limit alone, and the "…"
        // was appended unconditionally — reproduced rather than tidied up.
        assertThat(ClusterCommands.pingDetailText(ping(PingResult.STALE, 200, "other", "pod-b")))
                .isEqualTo("answered by 'pod-b' (podId=other…)");
    }

    @Test
    void detailCell_errorsAreCappedAt80() {
        String long_ = "x".repeat(200);

        String cell = ClusterCommands.pingDetailText(
                ping(PingResult.UNREACHABLE, 0, long_, null));

        assertThat(cell).hasSize(80).endsWith("…");
    }

    // ─── REASON cell ────────────────────────────────────────────────────────

    @Test
    void reasonCell_heartbeatReasonsNameTheTimestamp() {
        assertThat(ClusterCommands.pruneReasonText(new PruneCandidate(
                POD, PruneReason.STALE_HEARTBEAT, "2026-08-27T10:00:00Z", null)))
                .isEqualTo("stale heartbeat (2026-08-27T10:00:00Z)");
        assertThat(ClusterCommands.pruneReasonText(new PruneCandidate(
                POD, PruneReason.NO_HEARTBEAT, "2026-08-27T09:00:00Z", null)))
                .isEqualTo("no heartbeat (booted 2026-08-27T09:00:00Z)");
    }

    @Test
    void reasonCell_aMismatchReadsTheSameAsThePingRow() {
        // The regression this test was written for.
        PodPing probe = ping(PingResult.STALE, 200, "other", "pod-b");

        assertThat(ClusterCommands.pruneReasonText(new PruneCandidate(
                POD, PruneReason.LIVE_MISMATCH, probe.detail(), probe)))
                .isEqualTo("live mismatch (answered by 'pod-b' (podId=other…))");
    }

    @Test
    void reasonCell_unreachableIsCappedLikeThePingRow() {
        PodPing probe = ping(PingResult.UNREACHABLE, 0, "connect timed out", null);

        assertThat(ClusterCommands.pruneReasonText(new PruneCandidate(
                POD, PruneReason.UNREACHABLE, probe.detail(), probe)))
                .isEqualTo("unreachable (connect timed out)");
    }
}
