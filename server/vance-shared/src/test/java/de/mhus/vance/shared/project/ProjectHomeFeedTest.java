package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.megadodo.MegadodoService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Where a project lived, and when — the feed rows behind
 * {@code specification/public/megadodo-system.md} §3.2 {@code project.home}.
 *
 * <p>Two rules carry the whole design and neither is visible from the
 * happy path: a row is written on the <b>transition</b> and not on the
 * lease refresh that shares the same method, and the arrival row names
 * where the project came <b>from</b> — which is what closes the previous
 * residency when the departure was never observable.
 */
class ProjectHomeFeedTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String SELF_POD = "pod-b";
    private static final String SELF_NODE = "node-b";
    private static final String SELF_ADDRESS = "10.42.0.7:9990";
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final Instant LAST_SEEN = Instant.parse("2026-08-26T09:14:00Z");

    private ProjectRepository repository;
    private MongoTemplate mongoTemplate;
    private MegadodoService megadodo;
    private ProjectService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ProjectRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        megadodo = mock(MegadodoService.class);
        service = new ProjectService(
                repository, mongoTemplate, mock(AuditService.class), megadodo,
                mock(ObjectProvider.class));
    }

    @Test
    void claim_takingOverFromAnotherPod_recordsWhereItCameFrom() {
        // The row that makes a history reconstructable at all: a pod killed
        // outright writes no departure, so "arrived here, was on node-a"
        // is what closes the previous residency.
        given(staleOn("pod-a", "node-a", LAST_SEEN));

        service.claim(TENANT, PROJECT, SELF_POD, SELF_NODE, SELF_ADDRESS, TTL);

        // Not just "came from node-a" but when node-a was last alive: two
        // adjacent claims otherwise only say that something happened in
        // between, never how long the project was adrift.
        verify(megadodo).projectHomeClaimed(
                eq(TENANT), eq(PROJECT), eq(SELF_NODE), eq(SELF_POD), eq(SELF_ADDRESS),
                eq("node-a"), eq(LAST_SEEN));
    }

    @Test
    void claim_ofAnUnownedProject_recordsNoPreviousHome() {
        given(existing(null, null));

        service.claim(TENANT, PROJECT, SELF_POD, SELF_NODE, SELF_ADDRESS, TTL);

        verify(megadodo).projectHomeClaimed(
                eq(TENANT), eq(PROJECT), eq(SELF_NODE), eq(SELF_POD), eq(SELF_ADDRESS),
                eq(null), eq(null));
    }

    @Test
    void claim_thatIsOnlyALeaseRefresh_writesNoRow() {
        // Claiming is idempotent and doubles as the lease refresh, so every
        // path that touches the project comes through here. A row per call
        // would be a row per session create.
        given(existing(SELF_POD, SELF_NODE));

        service.claim(TENANT, PROJECT, SELF_POD, SELF_NODE, SELF_ADDRESS, TTL);

        verify(megadodo, never()).projectHomeClaimed(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void claim_rejectedByAnotherPodsLiveLease_writesNoRow() {
        // Nothing moved, so nothing happened worth recording. The caller
        // redirects to the holder.
        when(repository.findByTenantIdAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(existing("pod-a", "node-a")));
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(ProjectDocument.class)))
                .thenReturn(null);

        service.claim(TENANT, PROJECT, SELF_POD, SELF_NODE, SELF_ADDRESS, TTL);

        verify(megadodo, never()).projectHomeClaimed(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void releaseLeases_recordsOneRowPerProjectItLetGo() {
        // The only departure that can be observed: an expiring lease has
        // nobody left to write anything. Hence the read before the write —
        // after the unset, which projects were held is unknowable.
        when(mongoTemplate.find(any(Query.class), eq(ProjectDocument.class)))
                .thenReturn(List.of(existing(SELF_POD, SELF_NODE), other("billing")));
        givenReleaseModifies(1L);

        service.releaseLeases(SELF_POD, SELF_NODE, SELF_ADDRESS);

        verify(megadodo).projectHomeReleased(
                TENANT, PROJECT, SELF_NODE, SELF_POD, SELF_ADDRESS);
        verify(megadodo).projectHomeReleased(
                TENANT, "billing", SELF_NODE, SELF_POD, SELF_ADDRESS);
    }

    @Test
    void releaseLeases_projectClaimedByAnotherPodMeanwhile_writesNoRow() {
        // The lease had already expired and another pod took the project
        // between the read and the write. Announcing a release would tell an
        // operator this project has no home, when it is healthy elsewhere —
        // which is why the guarded write, not the batch, decides who gets a row.
        when(mongoTemplate.find(any(Query.class), eq(ProjectDocument.class)))
                .thenReturn(List.of(existing(SELF_POD, SELF_NODE)));
        givenReleaseModifies(0L);

        long released = service.releaseLeases(SELF_POD, SELF_NODE, SELF_ADDRESS);

        assertThat(released).isZero();
        verify(megadodo, never()).projectHomeReleased(any(), any(), any(), any(), any());
    }

    @Test
    void releaseLeases_withoutAPodId_doesNothing() {
        service.releaseLeases("", SELF_NODE, SELF_ADDRESS);

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), eq(ProjectDocument.class));
        verify(megadodo, never()).projectHomeReleased(any(), any(), any(), any(), any());
    }

    /** Every guarded release reports {@code modified} rows changed. */
    private void givenReleaseModifies(long modified) {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(modified);
        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class), eq(ProjectDocument.class)))
                .thenReturn(result);
    }

    /** Repository returns {@code current}; the CAS then succeeds. */
    private void given(ProjectDocument current) {
        when(repository.findByTenantIdAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(current));
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(ProjectDocument.class)))
                .thenReturn(existing(SELF_POD, SELF_NODE));
    }

    private static ProjectDocument existing(String podId, String node) {
        return ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(ProjectStatus.RUNNING)
                .homePodId(podId)
                .homeNode(node)
                .build();
    }

    /** A project the given pod held and stopped renewing at {@code lastSeen}. */
    private static ProjectDocument staleOn(String podId, String node, Instant lastSeen) {
        ProjectDocument doc = existing(podId, node);
        doc.setClaimedAt(lastSeen);
        return doc;
    }

    private static ProjectDocument other(String name) {
        return ProjectDocument.builder()
                .tenantId(TENANT)
                .name(name)
                .status(ProjectStatus.RUNNING)
                .homePodId(SELF_POD)
                .homeNode(SELF_NODE)
                .build();
    }
}
