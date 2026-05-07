package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.PodStatus;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ClusterServiceTest {

    private static final String CLUSTER = "test-cluster";
    private static final String ENDPOINT = "10.0.0.7:8080";

    private BrainPodService brainPodService;
    private ProjectService projectService;
    private LocationService locationService;
    private ClusterNodeNameGenerator nameGenerator;
    private ClusterProperties properties;
    private ClusterService service;

    @BeforeEach
    void setUp() {
        brainPodService = mock(BrainPodService.class);
        projectService = mock(ProjectService.class);
        locationService = mock(LocationService.class);
        nameGenerator = mock(ClusterNodeNameGenerator.class);

        properties = new ClusterProperties();
        properties.setId(CLUSTER);
        properties.setHeartbeatInterval(Duration.ofSeconds(60));
        properties.setStaleAfter(Duration.ofMinutes(2));
        properties.setRegistrationMaxRetries(5);

        when(locationService.getPodAddress()).thenReturn(ENDPOINT);
        when(projectService.findByPod(ENDPOINT)).thenReturn(List.of());
        when(nameGenerator.generate()).thenReturn("maya-prosser");

        service = new ClusterService(
                brainPodService, projectService, locationService, nameGenerator, properties);
        ReflectionTestUtils.setField(service, "buildVersion", "1.0.0-test");
    }

    @Test
    void onApplicationReady_writesStartingThenRunning() {
        service.onApplicationReady();

        verify(brainPodService, times(1)).register(any(BrainPodDocument.class));
        verify(brainPodService, times(1))
                .heartbeat(eq(service.selfPodId()), any(Instant.class),
                        eq(PodStatus.RUNNING), anyList());
        assertThat(service.selfNodeName()).isEqualTo("maya-prosser");
        assertThat(service.selfClusterId()).isEqualTo(CLUSTER);
    }

    @Test
    void onApplicationReady_collisionRetriesWithFreshGeneratedName() {
        when(nameGenerator.generate())
                .thenReturn("first-name")     // initial pick
                .thenReturn("second-name");   // retry pick

        // First register call throws taken-name; second succeeds.
        BrainPodService.NodeNameTakenException taken =
                new BrainPodService.NodeNameTakenException("taken", null);
        when(brainPodService.register(any(BrainPodDocument.class)))
                .thenThrow(taken)
                .thenAnswer(inv -> inv.getArgument(0));

        service.onApplicationReady();

        assertThat(service.selfNodeName()).isEqualTo("second-name");
        verify(brainPodService, times(2)).register(any(BrainPodDocument.class));
    }

    @Test
    void onApplicationReady_explicitConfiguredName_isNotAutoRenamedOnCollision() {
        properties.setNodeName("maya-explicit");
        when(brainPodService.register(any(BrainPodDocument.class)))
                .thenThrow(new BrainPodService.NodeNameTakenException("taken", null));

        // Operator asked for "maya-explicit" — refuse to silently rename.
        // onApplicationReady catches and logs (graceful boot), so no
        // exception bubbles up; the observable contract is "not
        // registered + no auto-rename via the generator".
        service.onApplicationReady();

        verify(nameGenerator, never()).generate();
        // Registered flag stays off → next heartbeat is a no-op.
        service.heartbeat();
        verify(brainPodService, never())
                .heartbeat(any(), any(), eq(PodStatus.RUNNING), anyList());
    }

    @Test
    void heartbeat_skippedWhenNotYetRegistered() {
        service.heartbeat();
        verify(brainPodService, never())
                .heartbeat(any(), any(), any(), anyList());
    }

    @Test
    void heartbeat_passesActiveProjectsAsTenantSlashName() {
        service.onApplicationReady();

        ProjectDocument p1 = ProjectDocument.builder()
                .tenantId("acme").name("instant-hole").podIp(ENDPOINT).build();
        ProjectDocument p2 = ProjectDocument.builder()
                .tenantId("acme").name("rocket-skates").podIp(ENDPOINT).build();
        when(projectService.findByPod(ENDPOINT)).thenReturn(List.of(p1, p2));

        service.heartbeat();

        verify(brainPodService, atLeastOnce())
                .heartbeat(eq(service.selfPodId()), any(Instant.class),
                        eq(PodStatus.RUNNING),
                        eq(List.of("acme/instant-hole", "acme/rocket-skates")));
    }

    @Test
    void heartbeat_recoversWhenRowMissing() {
        service.onApplicationReady();
        when(brainPodService.heartbeat(any(), any(), any(), anyList()))
                .thenThrow(new IllegalStateException("row missing"))
                .thenAnswer(inv -> null);

        service.heartbeat();

        // The recovery path re-runs onApplicationReady, which calls register again.
        verify(brainPodService, atLeastOnce()).register(any(BrainPodDocument.class));
    }
}
