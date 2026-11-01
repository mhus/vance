package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The actual state is a statement by the lease holder and only counts while
 * that lease lives; a crash must need no clean-up, and idle / suspended must
 * differ in who may wake them.
 */
class ProjectActualStateTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private static ProjectDocument held(ProjectActualState stored, Instant claimedAt) {
        return ProjectDocument.builder()
                .tenantId("acme")
                .name("p")
                .homePodId("pod-1")
                .claimedAt(claimedAt)
                .actualState(stored)
                .build();
    }

    @Test
    void storedStateCountsWhileTheLeaseLives() {
        ProjectDocument project = held(ProjectActualState.RUNNING, NOW.minusSeconds(5));

        assertThat(ProjectOwnership.effectiveActualState(project, NOW, TTL)).isEqualTo(ProjectActualState.RUNNING);
    }

    @Test
    void aLapsedLeaseReadsAsStopped_whateverWasLastWritten() {
        // A pod that died cannot write STOPPED; the stale RUNNING must not be believed.
        ProjectDocument project = held(ProjectActualState.RUNNING, NOW.minusSeconds(120));

        assertThat(ProjectOwnership.effectiveActualState(project, NOW, TTL)).isEqualTo(ProjectActualState.STOPPED);
    }

    @Test
    void aMidTransitionStateOfADeadPodAlsoReadsAsStopped() {
        ProjectDocument project = held(ProjectActualState.STOPPING, NOW.minusSeconds(120));

        assertThat(ProjectOwnership.effectiveActualState(project, NOW, TTL)).isEqualTo(ProjectActualState.STOPPED);
    }

    @Test
    void anUnclaimedProjectIsStopped() {
        ProjectDocument project = ProjectDocument.builder()
                .tenantId("acme")
                .name("p")
                .actualState(ProjectActualState.RUNNING)
                .build();

        assertThat(ProjectOwnership.effectiveActualState(project, NOW, TTL)).isEqualTo(ProjectActualState.STOPPED);
    }

    @Test
    void aNewProjectDefaultsToStopped() {
        assertThat(ProjectDocument.builder().build().getActualState()).isEqualTo(ProjectActualState.STOPPED);
    }

    @Test
    void idleWakesOnAccess_suspendedAndClosedDoNot() {
        assertThat(ProjectService.wakesOnAccess(ProjectStatus.INIT)).isTrue();
        assertThat(ProjectService.wakesOnAccess(ProjectStatus.RUNNING)).isTrue();
        assertThat(ProjectService.wakesOnAccess(ProjectStatus.IDLE)).isTrue();
        assertThat(ProjectService.wakesOnAccess(ProjectStatus.SUSPENDED)).isFalse();
        assertThat(ProjectService.wakesOnAccess(ProjectStatus.CLOSED)).isFalse();
        assertThat(ProjectService.wakesOnAccess(null)).isFalse();
    }

    @Test
    void recoveryDoesNotClaimAnOwnerForAnIdleProject() {
        assertThat(ProjectService.wantsToRun(ProjectStatus.IDLE)).isFalse();
        assertThat(ProjectService.wantsToRun(ProjectStatus.SUSPENDED)).isFalse();
        assertThat(ProjectService.wantsToRun(ProjectStatus.RUNNING)).isTrue();
    }
}
