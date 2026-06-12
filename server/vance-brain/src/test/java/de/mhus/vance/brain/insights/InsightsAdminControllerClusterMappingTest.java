package de.mhus.vance.brain.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.mhus.vance.api.insights.BrainPodInsightsDto;
import de.mhus.vance.api.insights.BrainPodProjectInsightsDto;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.PodStatus;
import de.mhus.vance.shared.project.LifecycleType;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pure-mapping tests for {@link InsightsAdminController#toClusterPodDto}.
 * Verifies the tenant-prefix filter on {@code activeProjects} — the
 * one piece of non-trivial logic on this endpoint — without booting
 * the controller stack.
 */
class InsightsAdminControllerClusterMappingTest {

    private static final Function<String, @Nullable ProjectDocument> EMPTY_LOOKUP = name -> null;

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

    private static ProjectDocument project(String name, ProjectStatus status,
                                           LifecycleType lifecycle, int score) {
        ProjectDocument p = new ProjectDocument();
        p.setTenantId("tenant-a");
        p.setName(name);
        p.setStatus(status);
        p.setLifecycleType(lifecycle);
        p.setHomeResourceScore(score);
        return p;
    }

    @Test
    void mapping_keepsOnlyTenantProjectsAndStripsThePrefix() {
        BrainPodDocument doc = podWith(List.of(
                "tenant-a/alpha",
                "tenant-a/beta",
                "tenant-b/gamma",
                "tenant-c/delta"));

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", null, false, EMPTY_LOOKUP);

        assertThat(dto.getTenantProjects())
                .extracting(BrainPodProjectInsightsDto::getName)
                .containsExactly("alpha", "beta");
        assertThat(dto.isSelfPod()).isTrue();
        assertThat(dto.isMaster()).isFalse();
        assertThat(dto.getStatus()).isEqualTo("RUNNING");
        assertThat(dto.isStale()).isFalse();
    }

    @Test
    void mapping_emptyAndNullActiveProjectsBecomeEmptyList() {
        BrainPodDocument empty = podWith(List.of());
        BrainPodDocument nullList = podWith(List.of());
        nullList.setActiveProjects(null);

        assertThat(InsightsAdminController.toClusterPodDto(
                empty, "tenant-a/", "other", null, false, EMPTY_LOOKUP).getTenantProjects()).isEmpty();
        assertThat(InsightsAdminController.toClusterPodDto(
                nullList, "tenant-a/", "other", null, false, EMPTY_LOOKUP).getTenantProjects()).isEmpty();
    }

    @Test
    void mapping_skipsNullEntriesAndSortsResult() {
        BrainPodDocument doc = podWith(Arrays.asList(
                "tenant-a/zeta",
                null,
                "tenant-a/alpha",
                "tenant-a/beta"));

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", null, false, EMPTY_LOOKUP);

        assertThat(dto.getTenantProjects())
                .extracting(BrainPodProjectInsightsDto::getName)
                .containsExactly("alpha", "beta", "zeta");
    }

    @Test
    void mapping_passesThroughStaleFlagFromCaller() {
        BrainPodDocument doc = podWith(List.of("tenant-a/alpha"));

        BrainPodInsightsDto fresh = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", null, false, EMPTY_LOOKUP);
        BrainPodInsightsDto stale = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", null, true, EMPTY_LOOKUP);

        assertThat(fresh.isStale()).isFalse();
        assertThat(stale.isStale()).isTrue();
    }

    @Test
    void mapping_selfPodIsFalseWhenIdsDiffer() {
        BrainPodDocument doc = podWith(List.of());

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "different-pod-uuid", null, false, EMPTY_LOOKUP);

        assertThat(dto.isSelfPod()).isFalse();
    }

    @Test
    void mapping_unknownStatusEnumWritesUNKNOWN() {
        BrainPodDocument doc = podWith(List.of());
        doc.setStatus(null);

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", null, false, EMPTY_LOOKUP);

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
                doc, "tenant-a/", "pod-uuid-1", null, false, EMPTY_LOOKUP);

        assertThat(dto.getTenantProjects())
                .extracting(BrainPodProjectInsightsDto::getName)
                .containsExactly("alpha");
    }

    @Test
    void mapping_masterFlagSetWhenPodIdMatchesLease() {
        BrainPodDocument doc = podWith(List.of());

        BrainPodInsightsDto master = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "other", "pod-uuid-1", false, EMPTY_LOOKUP);
        BrainPodInsightsDto follower = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "other", "another-pod", false, EMPTY_LOOKUP);
        BrainPodInsightsDto noLease = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "other", null, false, EMPTY_LOOKUP);

        assertThat(master.isMaster()).isTrue();
        assertThat(follower.isMaster()).isFalse();
        assertThat(noLease.isMaster()).isFalse();
    }

    @Test
    void mapping_enrichesProjectsViaLookup() {
        BrainPodDocument doc = podWith(List.of("tenant-a/alpha", "tenant-a/beta"));
        Map<String, ProjectDocument> projects = Map.of(
                "alpha", project("alpha", ProjectStatus.RUNNING, LifecycleType.PERMANENT, 5),
                "beta", project("beta", ProjectStatus.SUSPENDED, LifecycleType.EPHEMERAL, 1));

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", null, false, projects::get);

        assertThat(dto.getTenantProjects())
                .extracting(
                        BrainPodProjectInsightsDto::getName,
                        BrainPodProjectInsightsDto::getStatus,
                        BrainPodProjectInsightsDto::getLifecycleType,
                        BrainPodProjectInsightsDto::getHomeResourceScore)
                .containsExactly(
                        tuple("alpha", "RUNNING", "PERMANENT", 5),
                        tuple("beta", "SUSPENDED", "EPHEMERAL", 1));
    }

    @Test
    void mapping_unresolvedProjectKeepsNameWithNullAttributes() {
        BrainPodDocument doc = podWith(List.of("tenant-a/orphan"));

        BrainPodInsightsDto dto = InsightsAdminController.toClusterPodDto(
                doc, "tenant-a/", "pod-uuid-1", null, false, EMPTY_LOOKUP);

        assertThat(dto.getTenantProjects()).singleElement().satisfies(p -> {
            assertThat(p.getName()).isEqualTo("orphan");
            assertThat(p.getStatus()).isNull();
            assertThat(p.getLifecycleType()).isNull();
            assertThat(p.getHomeResourceScore()).isZero();
        });
    }
}
