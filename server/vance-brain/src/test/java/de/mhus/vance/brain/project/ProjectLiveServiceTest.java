package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.projects.ProjectLiveState;
import de.mhus.vance.api.projects.RunnerPhase;
import de.mhus.vance.api.projects.RunnerStatusDto;
import de.mhus.vance.api.ws.SignalFrame;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.signals.SignalBroadcaster;
import de.mhus.vance.shared.livestate.LiveStateStore;
import de.mhus.vance.shared.livestate.LiveStateStore.ProjectEntry;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectActualState;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import de.mhus.vance.shared.runner.RunnerBinding;
import de.mhus.vance.shared.runner.RunnerBindingService;
import de.mhus.vance.shared.runner.RunnerBindingService.Effective;
import de.mhus.vance.shared.runner.RunnerBindingService.Source;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectLiveServiceTest {

    private LiveStateStore store;
    private SignalBroadcaster signals;
    private ClusterService cluster;
    private VanceRedisMessagingService redis;
    private RunnerBindingService bindings;
    private PermissionService permissions;
    private SecurityContextFactory contexts;
    private ProjectLiveService service;

    @BeforeEach
    void setUp() {
        store = mock(LiveStateStore.class);
        signals = mock(SignalBroadcaster.class);
        cluster = mock(ClusterService.class);
        redis = mock(VanceRedisMessagingService.class);
        bindings = mock(RunnerBindingService.class);
        permissions = mock(PermissionService.class);
        contexts = mock(SecurityContextFactory.class);
        when(cluster.leaseTtl()).thenReturn(Duration.ofMinutes(5));
        service = new ProjectLiveService(store, signals, cluster, redis, bindings, permissions, contexts);
    }

    @Test
    void recordingAStateWritesTheEntryAndPushesItOnTheStatusPath() {
        when(store.putProject("acme", "p1", ProjectLiveState.RUNNING, Duration.ofMinutes(5)))
                .thenReturn(new ProjectEntry(ProjectLiveState.RUNNING, 1L, 2L));

        service.record("acme", "p1", ProjectLiveState.RUNNING);

        ArgumentCaptor<SignalFrame> frame = ArgumentCaptor.forClass(SignalFrame.class);
        verify(signals).broadcast(eq("acme"), frame.capture());
        assertThat(frame.getValue().getPath()).isEqualTo("_status");
        assertThat(frame.getValue().getSignal()).isEqualTo("project-state");
        assertThat(frame.getValue().getData())
                .containsEntry("project", "p1")
                .containsEntry("liveState", "RUNNING")
                .containsEntry("expiresAtMs", 2L);
    }

    @Test
    void aStoppedProjectStaysOnRecordLongerThanALease() {
        when(store.putProject(eq("acme"), eq("p1"), eq(ProjectLiveState.STOPPED), any()))
                .thenReturn(new ProjectEntry(ProjectLiveState.STOPPED, 1L, 2L));

        service.record("acme", "p1", ProjectLiveState.STOPPED);

        verify(store).putProject("acme", "p1", ProjectLiveState.STOPPED, Duration.ofHours(1));
    }

    private static ProjectDocument project(String holder, Instant claimedAt, ProjectActualState actual) {
        ProjectDocument doc = new ProjectDocument();
        doc.setName("p1");
        doc.setHomePodId(holder);
        doc.setClaimedAt(claimedAt);
        doc.setActualState(actual);
        return doc;
    }

    @Test
    void theLiveEntryWinsOverTheDocument() {
        when(store.getProject("acme", "p1"))
                .thenReturn(Optional.of(new ProjectEntry(ProjectLiveState.STARTING, 1L, 77L)));

        var view = service.liveViewOf("acme", project(null, null, null));

        assertThat(view.state()).isEqualTo(ProjectLiveState.STARTING);
        assertThat(view.expiresAtMs()).isEqualTo(77L);
    }

    @Test
    void aProjectWithoutEntryAndWithoutLeaseReadsAsStoppedNotUnknown() {
        when(store.getProject("acme", "p1")).thenReturn(Optional.empty());

        var view = service.liveViewOf("acme", project(null, null, null));

        assertThat(view.state()).isEqualTo(ProjectLiveState.STOPPED);
        assertThat(view.expiresAtMs()).isNull();
    }

    @Test
    void aProjectWithoutEntryButWithALiveLeaseReadsFromTheDocument() {
        when(store.getProject("acme", "p1")).thenReturn(Optional.empty());
        when(cluster.isHolderGone("pod-a")).thenReturn(false);

        var view = service.liveViewOf("acme", project("pod-a", Instant.now(), ProjectActualState.RUNNING));

        assertThat(view.state()).isEqualTo(ProjectLiveState.RUNNING);
    }

    @Test
    void aLeaseOfAGonePodDoesNotMakeTheProjectRun() {
        when(store.getProject("acme", "p1")).thenReturn(Optional.empty());
        when(cluster.isHolderGone("pod-a")).thenReturn(true);

        var view = service.liveViewOf("acme", project("pod-a", Instant.now(), ProjectActualState.RUNNING));

        assertThat(view.state()).isEqualTo(ProjectLiveState.STOPPED);
    }

    @Test
    void aStoppedEntryNeverRunsOutOnTheClient() {
        when(store.getProject("acme", "p1"))
                .thenReturn(Optional.of(new ProjectEntry(ProjectLiveState.STOPPED, 1L, 2L)));

        assertThat(service.liveViewOf("acme", project(null, null, null)).expiresAtMs())
                .isNull();
    }

    @Test
    void aStoppedFrameCarriesNoExpiry() {
        when(store.putProject(eq("acme"), eq("p1"), eq(ProjectLiveState.STOPPED), any()))
                .thenReturn(new ProjectEntry(ProjectLiveState.STOPPED, 1L, 2L));

        service.record("acme", "p1", ProjectLiveState.STOPPED);

        ArgumentCaptor<SignalFrame> frame = ArgumentCaptor.forClass(SignalFrame.class);
        verify(signals).broadcast(eq("acme"), frame.capture());
        assertThat(frame.getValue().getData()).containsEntry("expiresAtMs", null);
    }

    @Test
    void aBrokenStoreNeverFailsTheLifecycle() {
        when(store.putProject(any(), any(), any(), any())).thenThrow(new IllegalStateException("down"));

        service.record("acme", "p1", ProjectLiveState.RUNNING);

        verify(signals, never()).broadcast(any(), any());
    }

    @Test
    void renewingPushesTheNewExpiryOnlyForAnExistingEntry() {
        when(store.renewProject("acme", "p1", Duration.ofMinutes(5)))
                .thenReturn(Optional.of(new ProjectEntry(ProjectLiveState.RUNNING, 1L, 9L)));
        when(store.renewProject("acme", "gone", Duration.ofMinutes(5))).thenReturn(Optional.empty());

        assertThat(service.renew("acme", "p1")).isTrue();
        assertThat(service.renew("acme", "gone")).isFalse();

        verify(signals).broadcast(eq("acme"), any());
    }

    @Test
    void theStoredActualStateMapsOntoTheLiveState() {
        assertThat(ProjectLiveService.liveOf(ProjectActualState.STARTING)).isEqualTo(ProjectLiveState.STARTING);
        assertThat(ProjectLiveService.liveOf(ProjectActualState.STOPPING)).isEqualTo(ProjectLiveState.STOPPING);
        assertThat(ProjectLiveService.liveOf(null)).isEqualTo(ProjectLiveState.UNKNOWN);
    }

    @Test
    void aBoundRunnerWithoutAnEntryIsUnknown() {
        RunnerBinding binding = new RunnerBinding();
        binding.setRunnerId("runner-acme");
        when(bindings.resolve("acme", "p1")).thenReturn(Optional.of(new Effective(binding, Source.TENANT)));
        when(store.getRunner("acme", "runner-acme")).thenReturn(Optional.empty());

        RunnerStatusDto runner = service.runnerOf("acme", "p1").orElseThrow();

        assertThat(runner.getRunnerId()).isEqualTo("runner-acme");
        assertThat(runner.getPhase()).isEqualTo(RunnerPhase.UNKNOWN);
    }

    @Test
    void aProjectWithoutRunnerHasNoRunnerStatus() {
        when(bindings.resolve("acme", "p1")).thenReturn(Optional.empty());

        assertThat(service.runnerOf("acme", "p1")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectFramesReachOnlySubscribersWhoMayReadTheProject() {
        service.start();
        ArgumentCaptor<BiPredicate<ConnectionContext, SignalFrame>> audience =
                ArgumentCaptor.forClass(BiPredicate.class);
        verify(signals).restrictAudience(eq("_status"), audience.capture());
        ConnectionContext connection = mock(ConnectionContext.class);
        when(connection.getTenantId()).thenReturn("acme");
        SecurityContext security = mock(SecurityContext.class);
        when(contexts.fromConnection(connection)).thenReturn(security);
        when(permissions.check(security, new Resource.Project("acme", "p1"), Action.READ))
                .thenReturn(true);
        when(permissions.check(security, new Resource.Project("acme", "secret"), Action.READ))
                .thenReturn(false);

        SignalFrame allowed = SignalFrame.builder()
                .path("_status")
                .signal("project-state")
                .data(java.util.Map.of("project", "p1"))
                .build();
        SignalFrame denied = SignalFrame.builder()
                .path("_status")
                .signal("project-state")
                .data(java.util.Map.of("project", "secret"))
                .build();
        SignalFrame runner = SignalFrame.builder()
                .path("_status")
                .signal("runner-state")
                .data(java.util.Map.of("runnerId", "r1"))
                .build();

        assertThat(audience.getValue().test(connection, allowed)).isTrue();
        assertThat(audience.getValue().test(connection, denied)).isFalse();
        assertThat(audience.getValue().test(connection, runner)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRunnerAnnouncedByRedisIsPushedLocallyOnly() {
        service.start();
        ArgumentCaptor<BiConsumer<String, String>> listener = ArgumentCaptor.forClass(BiConsumer.class);
        verify(redis).subscribeAcrossTenants(eq(LiveStateStore.RUNNER_CHANNEL), listener.capture());
        RunnerStatusDto dto = RunnerStatusDto.builder()
                .runnerId("r1")
                .phase(RunnerPhase.READY)
                .restarts(0)
                .build();
        when(store.decodeRunner("body")).thenReturn(Optional.of(dto));

        listener.getValue().accept("vance:acme:runner-live", "body");

        ArgumentCaptor<SignalFrame> frame = ArgumentCaptor.forClass(SignalFrame.class);
        verify(signals).broadcastLocal(eq("acme"), frame.capture());
        verify(signals, never()).broadcast(any(), any());
        assertThat(frame.getValue().getSignal()).isEqualTo("runner-state");
        assertThat(frame.getValue().getData()).containsEntry("runnerId", "r1").containsEntry("phase", "READY");
    }
}
