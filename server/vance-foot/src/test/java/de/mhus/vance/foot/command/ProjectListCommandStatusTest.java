package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.projects.ProjectLiveState;
import de.mhus.vance.api.projects.RunnerPhase;
import de.mhus.vance.api.projects.RunnerStatusDto;
import de.mhus.vance.api.ws.ProjectSummary;
import org.junit.jupiter.api.Test;

/** The list shows what the web UI shows: an entry that ran out reads as unknown. */
class ProjectListCommandStatusTest {

    private static final long FUTURE = System.currentTimeMillis() + 60_000;
    private static final long PAST = System.currentTimeMillis() - 60_000;

    @Test
    void aLiveStateWithinItsLifeIsShown() {
        ProjectSummary p = ProjectSummary.builder()
                .name("a")
                .liveState(ProjectLiveState.RUNNING)
                .liveExpiresAtMs(FUTURE)
                .build();

        assertThat(ProjectListCommand.liveState(p)).isEqualTo("RUNNING");
    }

    @Test
    void aLiveStateThatRanOutIsUnknown() {
        ProjectSummary p = ProjectSummary.builder()
                .name("a")
                .liveState(ProjectLiveState.RUNNING)
                .liveExpiresAtMs(PAST)
                .build();

        assertThat(ProjectListCommand.liveState(p)).isEqualTo("UNKNOWN");
    }

    @Test
    void aBrainWithoutLiveStateReadsAsUnknown() {
        assertThat(ProjectListCommand.liveState(
                        ProjectSummary.builder().name("a").build()))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void aProjectWithoutRunnerShowsADash() {
        assertThat(ProjectListCommand.runner(ProjectSummary.builder().name("a").build()))
                .isEqualTo("-");
    }

    @Test
    void aRunnerShowsItsPhaseUntilItsEntryRunsOut() {
        RunnerStatusDto ready = RunnerStatusDto.builder()
                .runnerId("r1")
                .phase(RunnerPhase.READY)
                .expiresAtMs(FUTURE)
                .build();
        RunnerStatusDto old = RunnerStatusDto.builder()
                .runnerId("r1")
                .phase(RunnerPhase.READY)
                .expiresAtMs(PAST)
                .build();

        assertThat(ProjectListCommand.runner(
                        ProjectSummary.builder().name("a").runner(ready).build()))
                .isEqualTo("READY");
        assertThat(ProjectListCommand.runner(
                        ProjectSummary.builder().name("a").runner(old).build()))
                .isEqualTo("UNKNOWN");
    }
}
