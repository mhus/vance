package de.mhus.vance.brain.eddie;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.eddie.ChannelMode;
import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-format tests for the {@code <delegated_workers>} system-prompt
 * block — see {@code planning/eddie-moderator-erweiterung.md} §2.
 */
class EddieEngineDelegatedWorkersBlockTest {

    private static final Instant NOW = Instant.parse("2026-05-08T10:00:00Z");

    @Test
    void emptyOrNullList_returnsNull() {
        assertThat(EddieEngine.renderDelegatedWorkersBlock(null, 10, NOW)).isNull();
        assertThat(EddieEngine.renderDelegatedWorkersBlock(List.of(), 10, NOW)).isNull();
    }

    @Test
    void linksWithoutStatusOrSummary_areSkipped() {
        var bare = WorkerLinkSnapshot.builder()
                .workerProcessId("w-1")
                .workerProcessName("arthur")
                .workerProjectName("projA")
                .build();

        String block = EddieEngine.renderDelegatedWorkersBlock(List.of(bare), 10, NOW);

        assertThat(block).isNull();
    }

    @Test
    void rendersSingleEntry_withSourcePrefix_andSummary() {
        var link = WorkerLinkSnapshot.builder()
                .workerProcessId("w-1")
                .workerProcessName("arthur")
                .workerProjectName("auth-refactor")
                .workerStatus(ThinkProcessStatus.RUNNING)
                .lastSeen(NOW.minusSeconds(12))
                .triageSummary("Plan vorgelegt: Variante A oder B.")
                .channelMode(ChannelMode.MILESTONES)
                .build();

        String block = EddieEngine.renderDelegatedWorkersBlock(List.of(link), 10, NOW);

        assertThat(block).startsWith("## Delegated workers");
        assertThat(block).contains("- arthur-auth-refactor (running, 12s ago) — Plan vorgelegt: Variante A oder B.");
    }

    @Test
    void includesPlanModeWhenNotNormal_omitsWhenNormal() {
        var planning = WorkerLinkSnapshot.builder()
                .workerProcessId("w-1").workerProcessName("arthur").workerProjectName("p")
                .workerStatus(ThinkProcessStatus.RUNNING)
                .workerMode(ProcessMode.PLANNING)
                .lastSeen(NOW)
                .triageSummary("waiting for approval").build();
        var normal = WorkerLinkSnapshot.builder()
                .workerProcessId("w-2").workerProcessName("ford").workerProjectName("p")
                .workerStatus(ThinkProcessStatus.RUNNING)
                .workerMode(ProcessMode.NORMAL)
                .lastSeen(NOW)
                .triageSummary("running tests").build();

        String block = EddieEngine.renderDelegatedWorkersBlock(List.of(planning, normal), 10, NOW);

        assertThat(block).contains("(running/planning, 0s ago)");
        // NORMAL mode is the default — no slash-suffix.
        assertThat(block).contains("(running, 0s ago)");
    }

    @Test
    void sortsByLastSeenDesc_andRespectsLimit() {
        var older = WorkerLinkSnapshot.builder()
                .workerProcessId("w-old").workerProcessName("ford").workerProjectName("p")
                .workerStatus(ThinkProcessStatus.RUNNING)
                .lastSeen(NOW.minusSeconds(600))
                .triageSummary("old").build();
        var newer = WorkerLinkSnapshot.builder()
                .workerProcessId("w-new").workerProcessName("arthur").workerProjectName("p")
                .workerStatus(ThinkProcessStatus.RUNNING)
                .lastSeen(NOW.minusSeconds(5))
                .triageSummary("new").build();

        String block = EddieEngine.renderDelegatedWorkersBlock(List.of(older, newer), /*limit=*/ 1, NOW);

        // Newer first, older clipped by limit.
        assertThat(block).contains("arthur-p");
        assertThat(block).doesNotContain("ford-p");
    }

    @Test
    void relativeAge_formatsAcrossUnits() {
        assertThat(EddieEngine.relativeAge(NOW.minusSeconds(45), NOW)).isEqualTo("45s ago");
        assertThat(EddieEngine.relativeAge(NOW.minusSeconds(60 * 5), NOW)).isEqualTo("5min ago");
        assertThat(EddieEngine.relativeAge(NOW.minusSeconds(3600 * 3), NOW)).isEqualTo("3h ago");
        assertThat(EddieEngine.relativeAge(NOW.minusSeconds(3600 * 24 * 3), NOW)).isEqualTo("3d ago");
        // Future timestamps clamp to "0s ago" rather than going negative.
        assertThat(EddieEngine.relativeAge(NOW.plusSeconds(10), NOW)).isEqualTo("0s ago");
    }

    @Test
    void workerLabel_fallsBackThroughTheKnownFields() {
        assertThat(EddieEngine.workerLabel(WorkerLinkSnapshot.builder()
                .workerProcessId("id").workerProcessName("arthur").workerProjectName("proj")
                .build()))
                .isEqualTo("arthur-proj");
        assertThat(EddieEngine.workerLabel(WorkerLinkSnapshot.builder()
                .workerProcessId("id").workerProcessName("ford").build()))
                .isEqualTo("ford");
        assertThat(EddieEngine.workerLabel(WorkerLinkSnapshot.builder()
                .workerProcessId("id-only").build()))
                .isEqualTo("id-only");
    }
}
