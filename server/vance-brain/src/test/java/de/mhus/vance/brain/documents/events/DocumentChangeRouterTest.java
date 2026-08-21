package de.mhus.vance.brain.documents.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.document.DocumentChangedEvent;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import io.micrometer.core.instrument.Counter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class DocumentChangeRouterTest {

    private static final String TENANT = "acme";
    private static final String SELF_NODE = "maya-prosser";
    private static final String OTHER_NODE = "ford-prefect";
    private static final String OTHER_ENDPOINT = "10.0.0.8:8080";
    private static final String SELF_POD = "pod-self";
    private static final String OTHER_POD = "pod-other";
    private static final java.time.Duration LEASE_TTL = java.time.Duration.ofMinutes(5);

    private ProjectService projectService;
    private ClusterService clusterService;
    private ApplicationEventPublisher eventPublisher;
    private DocumentChangeDispatcher dispatcher;
    private MetricService metrics;
    private Counter dummyCounter;

    private DocumentChangeRouter router;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        clusterService = mock(ClusterService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        dispatcher = mock(DocumentChangeDispatcher.class);
        metrics = mock(MetricService.class);
        dummyCounter = mock(Counter.class);

        when(clusterService.selfNodeName()).thenReturn(SELF_NODE);
        when(clusterService.selfPodId()).thenReturn(SELF_POD);
        when(clusterService.leaseTtl()).thenReturn(LEASE_TTL);
        when(metrics.counter(any(), any(String[].class))).thenReturn(dummyCounter);
        when(metrics.counter(any())).thenReturn(dummyCounter);

        router = new DocumentChangeRouter(
                projectService, clusterService, eventPublisher, dispatcher, metrics);
    }

    @Test
    void tenantProject_broadcasts_to_self_and_remote_live_pods() {
        when(clusterService.liveClusterPods()).thenReturn(List.of(
                pod(SELF_NODE, "10.0.0.7:8080"),
                pod(OTHER_NODE, OTHER_ENDPOINT)));

        DocumentChangeRouter.Classification c = router.classify(upserted("_tenant",
                "_vance/server-tools/zoho_imap.yaml"));

        assertThat(c.kind()).isEqualTo(DocumentChangeRouter.Kind.BROADCAST);
        assertThat(c.fireSelf()).isTrue();
        assertThat(c.remoteEndpoints()).containsExactly(OTHER_ENDPOINT);
    }

    @Test
    void tenantProject_broadcast_alone_in_cluster_falls_back_to_self() {
        when(clusterService.liveClusterPods()).thenReturn(List.of(
                pod(SELF_NODE, "10.0.0.7:8080")));

        DocumentChangeRouter.Classification c = router.classify(upserted("_tenant",
                "_vance/server-tools/zoho_imap.yaml"));

        assertThat(c.kind()).isEqualTo(DocumentChangeRouter.Kind.SELF);
        assertThat(c.fireSelf()).isTrue();
        assertThat(c.remoteEndpoints()).isEmpty();
    }

    @Test
    void userHubProject_fires_local_only() {
        // Podless: no remote holder to notify, but Eddie very likely runs on
        // this pod and its scopes must refresh.
        DocumentChangeRouter.Classification c = router.classify(upserted("_user_mike",
                "documents/note.md"));

        assertThat(c.kind()).isEqualTo(DocumentChangeRouter.Kind.SELF);
        assertThat(c.fireSelf()).isTrue();
        assertThat(c.remoteEndpoints()).isEmpty();
    }

    @Test
    void regularProject_leaseHeldBySelf_fires_local_only() {
        when(projectService.findByTenantAndName(TENANT, "mail-assistant"))
                .thenReturn(Optional.of(leased("mail-assistant", SELF_POD, SELF_NODE)));

        DocumentChangeRouter.Classification c = router.classify(upserted("mail-assistant",
                "documents/mail-triage.js"));

        assertThat(c.kind()).isEqualTo(DocumentChangeRouter.Kind.SELF);
        assertThat(c.fireSelf()).isTrue();
        assertThat(c.remoteEndpoints()).isEmpty();
    }

    @Test
    void regularProject_leaseHeldByRemotePod_fires_local_and_remote() {
        when(projectService.findByTenantAndName(TENANT, "mail-assistant"))
                .thenReturn(Optional.of(leased("mail-assistant", OTHER_POD, OTHER_NODE)));
        when(clusterService.resolveEndpointByPodId(OTHER_POD))
                .thenReturn(Optional.of(OTHER_ENDPOINT));

        DocumentChangeRouter.Classification c = router.classify(upserted("mail-assistant",
                "documents/mail-triage.js"));

        assertThat(c.kind()).isEqualTo(DocumentChangeRouter.Kind.REMOTE);
        assertThat(c.fireSelf())
                .as("the writing pod always refreshes itself, holder or not")
                .isTrue();
        assertThat(c.remoteEndpoints()).containsExactly(OTHER_ENDPOINT);
    }

    @Test
    void regularProject_holderRowGone_fires_local_only() {
        when(projectService.findByTenantAndName(TENANT, "mail-assistant"))
                .thenReturn(Optional.of(leased("mail-assistant", OTHER_POD, OTHER_NODE)));
        when(clusterService.resolveEndpointByPodId(OTHER_POD)).thenReturn(Optional.empty());

        DocumentChangeRouter.Classification c = router.classify(upserted("mail-assistant",
                "documents/mail-triage.js"));

        assertThat(c.kind()).isEqualTo(DocumentChangeRouter.Kind.SELF);
        assertThat(c.fireSelf()).isTrue();
        assertThat(c.remoteEndpoints()).isEmpty();
    }

    @Test
    void regularProject_expiredLease_fires_local_only() {
        // Nobody owns the project, so there is no remote target — but this pod
        // may well hold a ServerToolRegistry scope for it, because scopes are
        // populated by reads, not by ownership. Dropping the event here is the
        // bug the lease track exists to fix.
        when(projectService.findByTenantAndName(TENANT, "mail-assistant"))
                .thenReturn(Optional.of(expiredLease("mail-assistant")));

        DocumentChangeRouter.Classification c = router.classify(upserted("mail-assistant",
                "documents/mail-triage.js"));

        assertThat(c.kind()).isEqualTo(DocumentChangeRouter.Kind.SELF);
        assertThat(c.fireSelf()).isTrue();
        assertThat(c.remoteEndpoints()).isEmpty();
    }

    @Test
    void regularProject_unleased_fires_local_only() {
        when(projectService.findByTenantAndName(TENANT, "mail-assistant"))
                .thenReturn(Optional.of(leased("mail-assistant", null, null)));

        DocumentChangeRouter.Classification c = router.classify(upserted("mail-assistant",
                "documents/mail-triage.js"));

        assertThat(c.kind()).isEqualTo(DocumentChangeRouter.Kind.SELF);
    }

    @Test
    void regularProject_unknown_project_fires_local_only() {
        when(projectService.findByTenantAndName(TENANT, "mail-assistant"))
                .thenReturn(Optional.empty());

        DocumentChangeRouter.Classification c = router.classify(upserted("mail-assistant",
                "documents/mail-triage.js"));

        assertThat(c.kind()).isEqualTo(DocumentChangeRouter.Kind.SELF);
    }

    @Test
    void onDocumentChanged_self_target_publishes_routed_event_and_enqueues_nothing() {
        when(projectService.findByTenantAndName(TENANT, "mail-assistant"))
                .thenReturn(Optional.of(leased("mail-assistant", SELF_POD, SELF_NODE)));

        router.onDocumentChanged(upserted("mail-assistant", "documents/mail-triage.js"));

        verify(eventPublisher, times(1)).publishEvent(any(RoutedDocumentChangedEvent.class));
        verify(dispatcher, never()).enqueue(any(), any());
    }

    @Test
    void onDocumentChanged_remote_target_enqueues_and_also_publishes_local() {
        when(projectService.findByTenantAndName(TENANT, "mail-assistant"))
                .thenReturn(Optional.of(leased("mail-assistant", OTHER_POD, OTHER_NODE)));
        when(clusterService.resolveEndpointByPodId(OTHER_POD))
                .thenReturn(Optional.of(OTHER_ENDPOINT));

        router.onDocumentChanged(upserted("mail-assistant", "documents/mail-triage.js"));

        verify(eventPublisher, times(1)).publishEvent(any(RoutedDocumentChangedEvent.class));
        verify(dispatcher, times(1)).enqueue(eq(OTHER_ENDPOINT), any(DocumentChangedEvent.class));
    }

    @Test
    void onDocumentChanged_broadcast_publishes_local_AND_enqueues_remotes() {
        when(clusterService.liveClusterPods()).thenReturn(List.of(
                pod(SELF_NODE, "10.0.0.7:8080"),
                pod(OTHER_NODE, OTHER_ENDPOINT)));

        router.onDocumentChanged(upserted("_tenant", "_vance/server-tools/zoho_imap.yaml"));

        verify(eventPublisher, times(1)).publishEvent(any(RoutedDocumentChangedEvent.class));
        verify(dispatcher, times(1)).enqueue(eq(OTHER_ENDPOINT), any(DocumentChangedEvent.class));
    }

    @Test
    void onDocumentChanged_router_exception_does_not_propagate() {
        when(projectService.findByTenantAndName(any(), any()))
                .thenThrow(new RuntimeException("mongo down"));

        // Must not throw — the document write must not be unwound.
        router.onDocumentChanged(upserted("mail-assistant", "documents/x.md"));
    }

    // ─── helpers ──────────────────────────────────────────────────

    private static DocumentChangedEvent upserted(String projectId, String path) {
        return new DocumentChangedEvent.Upserted(TENANT, projectId, path, "doc-id-1");
    }

    private static BrainPodDocument pod(String nodeName, String endpoint) {
        BrainPodDocument doc = new BrainPodDocument();
        doc.setNodeName(nodeName);
        doc.setEndpoint(endpoint);
        return doc;
    }

    private static ProjectDocument leased(String name, String podId, String nodeName) {
        ProjectDocument doc = new ProjectDocument();
        doc.setTenantId(TENANT);
        doc.setName(name);
        doc.setHomePodId(podId);
        doc.setHomeNode(nodeName);
        if (podId != null) doc.setClaimedAt(java.time.Instant.now());
        return doc;
    }

    /** A lease whose holder stopped renewing — nobody owns the project. */
    private static ProjectDocument expiredLease(String name) {
        ProjectDocument doc = leased(name, OTHER_POD, OTHER_NODE);
        doc.setClaimedAt(java.time.Instant.now().minus(LEASE_TTL).minusSeconds(1));
        return doc;
    }
}
