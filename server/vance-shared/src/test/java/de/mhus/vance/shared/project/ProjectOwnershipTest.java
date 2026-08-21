package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The lease predicate is the single point where "does anyone own this project"
 * is decided, so its edge cases are worth pinning: every routing decision, the
 * claim CAS and the recovery selectors read the same answer.
 */
class ProjectOwnershipTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    private static ProjectDocument leased(String podId, Instant claimedAt) {
        return ProjectDocument.builder()
                .tenantId("acme").name("test1")
                .homePodId(podId).claimedAt(claimedAt)
                .build();
    }

    @Test
    void freshlyRenewedLease_hasLiveOwner() {
        ProjectDocument p = leased("pod-a", NOW.minusSeconds(30));

        assertThat(ProjectOwnership.liveOwnerPodId(p, NOW, TTL)).contains("pod-a");
        assertThat(ProjectOwnership.isOwnedBy(p, "pod-a", NOW, TTL)).isTrue();
        assertThat(ProjectOwnership.isUnowned(p, NOW, TTL)).isFalse();
    }

    @Test
    void leaseOlderThanTtl_isUnowned() {
        ProjectDocument p = leased("pod-a", NOW.minus(TTL).minusSeconds(1));

        assertThat(ProjectOwnership.liveOwnerPodId(p, NOW, TTL)).isEmpty();
        assertThat(ProjectOwnership.isOwnedBy(p, "pod-a", NOW, TTL)).isFalse();
        assertThat(ProjectOwnership.isUnowned(p, NOW, TTL)).isTrue();
    }

    @Test
    void leaseExactlyAtTtlBoundary_stillHolds() {
        // Expiry is strict: at the boundary the holder is still considered to
        // be renewing. Stealing on the exact tick would make the outcome
        // depend on scheduling jitter.
        ProjectDocument p = leased("pod-a", NOW.minus(TTL));

        assertThat(ProjectOwnership.liveOwnerPodId(p, NOW, TTL)).contains("pod-a");
    }

    @Test
    void holderWithoutRenewalTimestamp_isUnowned() {
        // Cannot be validated, so it must not strand the project forever.
        ProjectDocument p = leased("pod-a", null);

        assertThat(ProjectOwnership.liveOwnerPodId(p, NOW, TTL)).isEmpty();
    }

    @Test
    void renewalTimestampInTheFuture_stillHolds() {
        // Clock skew between pods: the holder is clearly renewing. Stealing
        // because our own clock runs behind would be the worse failure.
        ProjectDocument p = leased("pod-a", NOW.plusSeconds(90));

        assertThat(ProjectOwnership.liveOwnerPodId(p, NOW, TTL)).contains("pod-a");
    }

    @Test
    void neverClaimed_isUnowned() {
        assertThat(ProjectOwnership.liveOwnerPodId(leased(null, null), NOW, TTL)).isEmpty();
        assertThat(ProjectOwnership.liveOwnerPodId(leased("", NOW), NOW, TTL)).isEmpty();
    }

    @Test
    void isOwnedBy_differentPod_false() {
        ProjectDocument p = leased("pod-a", NOW);

        assertThat(ProjectOwnership.isOwnedBy(p, "pod-b", NOW, TTL)).isFalse();
    }
}
