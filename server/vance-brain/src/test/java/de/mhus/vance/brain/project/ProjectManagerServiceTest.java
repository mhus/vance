package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.projects.ProjectLiveState;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectActualState;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ProjectManagerService#findProjectEndpoint} must not route to a pod
 * that stopped renewing its lease — otherwise a session whose holder crashed
 * (or moved host IP) stays permanently unreachable, every resume tunnelled to
 * a dead endpoint (observed 2026-07-01).
 *
 * <p>The liveness question is now answered by the lease on the document itself
 * ({@code ProjectOwnership}, unit-tested separately), so what we assert here is
 * that {@code findProjectEndpoint} goes through it and never resolves an
 * endpoint for an expired lease.
 */
class ProjectManagerServiceTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String SELF_POD = "pod-self";

    private final ProjectService projectService = mock(ProjectService.class);
    private final ClusterService clusterService = mock(ClusterService.class);
    private final ProjectLiveService projectLive = mock(ProjectLiveService.class);
    private final ProjectManagerService manager =
            new ProjectManagerService(projectService, clusterService, projectLive);

    @BeforeEach
    void stubCluster() {
        when(clusterService.leaseTtl()).thenReturn(TTL);
        when(clusterService.selfPodId()).thenReturn(SELF_POD);
        when(clusterService.selfNodeName()).thenReturn("self-node");
        when(clusterService.selfEndpoint()).thenReturn("self:9990");
    }

    private void givenLease(String podId, Instant claimedAt) {
        when(projectService.findByTenantAndName("acme", "test1"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme")
                        .name("test1")
                        .homePodId(podId)
                        .homeNode("naga-vorlon")
                        .claimedAt(claimedAt)
                        .build()));
    }

    @Test
    void validLease_resolvesHolderEndpoint() {
        givenLease("pod-holder", Instant.now());
        when(clusterService.resolveEndpointByPodId("pod-holder")).thenReturn(Optional.of("192.168.1.113:9991"));

        assertThat(manager.findProjectEndpoint("acme", "test1")).contains("192.168.1.113:9991");
    }

    @Test
    void expiredLease_returnsEmptySoTheWsFallsBackToLocal() {
        givenLease("pod-holder", Instant.now().minus(TTL).minusSeconds(1));

        // No endpoint stub on purpose: an expired lease must be rejected
        // before an endpoint is ever looked up. That empty is what lets the
        // WS-receiving pod serve the session locally.
        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }

    @Test
    void leaseWithoutRenewalTimestamp_returnsEmpty() {
        givenLease("pod-holder", null);

        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }

    @Test
    void holderRowGone_returnsEmpty() {
        givenLease("pod-holder", Instant.now());
        when(clusterService.resolveEndpointByPodId("pod-holder")).thenReturn(Optional.empty());

        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }

    @Test
    void unclaimedProject_returnsEmpty() {
        givenLease(null, null);

        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }

    @Test
    void homelessTenantProject_returnsEmptyWithoutTouchingTheLease() {
        assertThat(manager.findProjectEndpoint("acme", "_tenant")).isEmpty();
        org.mockito.Mockito.verify(projectService, org.mockito.Mockito.never()).findByTenantAndName("acme", "_tenant");
    }

    @Test
    void userHub_resolvesItsHolderLikeAnyOtherProject() {
        when(projectService.findByTenantAndName("acme", "_user_marvin"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme")
                        .name("_user_marvin")
                        .homePodId("pod-holder")
                        .homeNode("naga-vorlon")
                        .claimedAt(Instant.now())
                        .build()));
        when(clusterService.resolveEndpointByPodId("pod-holder")).thenReturn(Optional.of("192.168.1.113:9991"));

        assertThat(manager.findProjectEndpoint("acme", "_user_marvin")).contains("192.168.1.113:9991");
    }

    // ─── claiming from a holder that is gone ─────────────────────────────────

    private void givenClaimRejectedBecauseOf(String holderPod, Instant claimedAt) {
        givenLease(holderPod, claimedAt);
        when(projectService.claim(anyString(), anyString(), anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(Optional.empty());
    }

    @Test
    void aLeaseOfAPodThatIsGone_isTakenOverInsteadOfWaitingOutTheTtl() {
        Instant claimed = Instant.now().minusSeconds(150);
        givenClaimRejectedBecauseOf("pod-dead", claimed);
        when(clusterService.isHolderGone("pod-dead")).thenReturn(true);
        ProjectDocument taken = ProjectDocument.builder()
                .tenantId("acme")
                .name("test1")
                .homePodId(SELF_POD)
                .build();
        when(projectService.claimFromGoneHolder(any(ProjectDocument.class), anyString(), any(), any()))
                .thenReturn(Optional.of(taken));

        assertThat(manager.claimForLocalPod("acme", "test1")).isSameAs(taken);

        verify(projectService).claimFromGoneHolder(any(ProjectDocument.class), anyString(), any(), any());
    }

    @Test
    void aLeaseOfALivePod_isNeverTaken() {
        givenClaimRejectedBecauseOf("pod-alive", Instant.now());
        when(clusterService.isHolderGone("pod-alive")).thenReturn(false);

        assertThatThrownBy(() -> manager.claimForLocalPod("acme", "test1"))
                .isInstanceOf(ProjectManagerService.ClaimRejectedException.class);

        verify(projectService, never()).claimFromGoneHolder(any(), any(), any(), any());
    }

    @Test
    void aTakeoverThatLosesTheCompareAndSet_isARejection() {
        // The holder renewed between the read and the write: it was alive after all.
        givenClaimRejectedBecauseOf("pod-dead", Instant.now().minusSeconds(150));
        when(clusterService.isHolderGone("pod-dead")).thenReturn(true);
        when(projectService.claimFromGoneHolder(any(ProjectDocument.class), anyString(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.claimForLocalPod("acme", "test1"))
                .isInstanceOf(ProjectManagerService.ClaimRejectedException.class);
    }

    @Test
    void theRedirectingClaim_takesOverFromAGoneHolderToo() {
        givenClaimRejectedBecauseOf("pod-dead", Instant.now().minusSeconds(150));
        when(clusterService.isHolderGone("pod-dead")).thenReturn(true);
        ProjectDocument taken =
                ProjectDocument.builder().tenantId("acme").name("test1").build();
        when(projectService.claimFromGoneHolder(any(ProjectDocument.class), anyString(), any(), any()))
                .thenReturn(Optional.of(taken));

        ProjectManagerService.ClaimResult result = manager.claimForLocalPodOrRedirect("acme", "test1");

        assertThat(result).isInstanceOf(ProjectManagerService.ClaimResult.Local.class);
    }

    // ─── the pod's score follows the leases right away ───────────────────────

    // ─── the live state follows the lease ───────────────────────────────────

    @Test
    void aClaim_recordsTheLiveStateOfTheProject() {
        ProjectDocument claimed = ProjectDocument.builder()
                .tenantId("acme")
                .name("test1")
                .homePodId(SELF_POD)
                .actualState(ProjectActualState.STOPPED)
                .build();
        when(projectService.claim(anyString(), anyString(), anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(claimed));

        manager.claimForLocalPod("acme", "test1");

        verify(projectLive).recordActual("acme", "test1", ProjectActualState.STOPPED);
    }

    @Test
    void aTakeover_recordsTheLiveStateOfTheProject() {
        givenClaimRejectedBecauseOf("pod-dead", Instant.now().minusSeconds(150));
        when(clusterService.isHolderGone("pod-dead")).thenReturn(true);
        ProjectDocument taken = ProjectDocument.builder()
                .tenantId("acme")
                .name("test1")
                .homePodId(SELF_POD)
                .actualState(ProjectActualState.STOPPED)
                .build();
        when(projectService.claimFromGoneHolder(any(ProjectDocument.class), anyString(), any(), any()))
                .thenReturn(Optional.of(taken));

        manager.claimForLocalPod("acme", "test1");

        verify(projectLive).recordActual("acme", "test1", ProjectActualState.STOPPED);
    }

    @Test
    void aRelease_recordsTheProjectAsStopped() {
        when(projectService.findByTenantAndName("acme", "test1"))
                .thenReturn(Optional.of(
                        ProjectDocument.builder().tenantId("acme").name("test1").build()));
        when(projectService.releaseLease(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        manager.releaseLocalLease("acme", "test1");

        verify(projectLive).record("acme", "test1", ProjectLiveState.STOPPED);
    }

    @Test
    void aReleaseThatDidNotHappen_recordsNothing() {
        when(projectService.releaseLease(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        manager.releaseLocalLease("acme", "test1");

        verify(projectLive, never()).record(anyString(), anyString(), any(ProjectLiveState.class));
    }

    @Test
    void aClaim_addsTheProjectsScoreToThisPod_beforeTheNextHeartbeat() {
        givenLease(null, null);
        ProjectDocument claimed = ProjectDocument.builder()
                .tenantId("acme")
                .name("test1")
                .homePodId(SELF_POD)
                .homeResourceScore(3)
                .build();
        when(projectService.claim(anyString(), anyString(), anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(claimed));

        manager.claimForLocalPod("acme", "test1");

        verify(clusterService).reserveScore(SELF_POD, 3);
    }

    @Test
    void renewingALeaseWeAlreadyHold_addsNothing() {
        givenLease(SELF_POD, Instant.now());
        ProjectDocument claimed = ProjectDocument.builder()
                .tenantId("acme")
                .name("test1")
                .homePodId(SELF_POD)
                .homeResourceScore(3)
                .build();
        when(projectService.claim(anyString(), anyString(), anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(claimed));

        manager.claimForLocalPod("acme", "test1");

        verify(clusterService, never()).reserveScore(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void releasingTheLease_takesTheScoreOffThisPod() {
        when(projectService.findByTenantAndName("acme", "test1"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme")
                        .name("test1")
                        .homeResourceScore(2)
                        .build()));
        when(projectService.releaseLease(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);

        assertThat(manager.releaseLocalLease("acme", "test1")).isTrue();

        verify(clusterService).reserveScore(SELF_POD, -2);
    }

    @Test
    void aReleaseThatDidNotHappen_changesNoScore() {
        when(projectService.findByTenantAndName("acme", "test1"))
                .thenReturn(Optional.of(
                        ProjectDocument.builder().tenantId("acme").name("test1").build()));
        when(projectService.releaseLease(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(false);

        assertThat(manager.releaseLocalLease("acme", "test1")).isFalse();

        verify(clusterService, never()).reserveScore(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }
}
