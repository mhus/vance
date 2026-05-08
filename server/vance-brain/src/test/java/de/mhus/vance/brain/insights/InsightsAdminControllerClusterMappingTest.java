package de.mhus.vance.brain.insights;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.insights.BrainPodInsightsDto;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.PodStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-mapping tests for {@link InsightsAdminController#toClusterPodDto}.
 * Verifies the tenant-prefix filter on {@code activeProjects} — the
 * one piece of non-trivial logic on this endpoint — without booting
 * the controller stack.
 */
class InsightsAdminControllerClusterMappingTest {

    private static BrainPodDocument podWith(List<String> activeProjects) {
        return BrainPodDocument.builder()
                .nodeName("maya-prosser")
                .podId("pod-uuid-1")
                .clusterId("default")
                .endpoint("10.0.0.7:8080")
                .status(PodStatus.RUNNING)
                .bootedAt(Instant.parse("2026-05-08T07:00:00Z"))
                .lastHeartbeatAt(Instant.parse("2026-05-08T07:05:00Z"))
                .version("dev")
                .activeProjects(activeProjects)
                .build();
    }

    @Test
    void mapping_keepsOnlyTenantProjectsAndStripsThePrefix() {
        BrainPodDocument doc = podWith(List.of(
                "tenant-a/alpha",
                "tenant-a/beta",
                "tenant-b/gamma",
                "tenant-c/delta"));

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", false);

        assertThat(dto.getTenantProjects()).containsExactly("alpha", "beta");
        assertThat(dto.isSelfPod()).isTrue();
        assertThat(dto.getStatus()).isEqualTo("RUNNING");
        assertThat(dto.isStale()).isFalse();
    }

    @Test
    void mapping_emptyAndNullActiveProjectsBecomeEmptyList() {
        BrainPodDocument empty = podWith(List.of());
        BrainPodDocument nullList = podWith(List.of());
        nullList.setActiveProjects(null);

        assertThat(InsightsAdminController.toClusterPodDto(
                empty, "tenant-a/", "other", false).getTenantProjects()).isEmpty();
        assertThat(InsightsAdminController.toClusterPodDto(
                nullList, "tenant-a/", "other", false).getTenantProjects()).isEmpty();
    }

    @Test
    void mapping_skipsNullEntriesAndSortsResult() {
        BrainPodDocument doc = podWith(Arrays.asList(
                "tenant-a/zeta",
                null,
                "tenant-a/alpha",
                "tenant-a/beta"));

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", false);

        assertThat(dto.getTenantProjects()).containsExactly("alpha", "beta", "zeta");
    }

    @Test
    void mapping_passesThroughStaleFlagFromCaller() {
        BrainPodDocument doc = podWith(List.of("tenant-a/alpha"));

        BrainPodInsightsDto fresh = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", false);
        BrainPodInsightsDto stale = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", true);

        assertThat(fresh.isStale()).isFalse();
        assertThat(stale.isStale()).isTrue();
    }

    @Test
    void mapping_selfPodIsFalseWhenIdsDiffer() {
        BrainPodDocument doc = podWith(List.of());

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "different-pod-uuid", false);

        assertThat(dto.isSelfPod()).isFalse();
    }

    @Test
    void mapping_unknownStatusEnumWritesUNKNOWN() {
        BrainPodDocument doc = podWith(List.of());
        doc.setStatus(null);

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", false);

        assertThat(dto.getStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void mapping_tenantPrefixWithSlashOnlyMatchesExactPrefix() {
        // Edge: an attacker-controlled project named "tenant-aa/foo" must not
        // leak when the requesting tenant is "tenant-a".
        BrainPodDocument doc = podWith(List.of(
                "tenant-a/alpha",
                "tenant-aa/foo",
                "tenant-ab/bar"));

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", false);

        assertThat(dto.getTenantProjects()).containsExactly("alpha");
    }
}
