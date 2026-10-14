package de.mhus.vance.shared.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.metric.MetricService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The acquisition decision logic of {@link ResourceLeaseService} — funnel
 * order, rollback, NOOP path, busy diagnosis, check-at-use. The Mongo
 * atomicity itself (conditional upsert under concurrency, the $expr reap
 * filter on server time) is the E2E lane's concern
 * ({@code qa/ai-test/ResourceLeaseE2ETest}); here MongoTemplate is a mock
 * that records the funnel.
 */
class ResourceLeaseServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj-1";
    private static final String USER = "alice";

    private MongoTemplate mongoTemplate;
    private LeaseDefinitionLoader loader;
    private ResourceLeaseService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        loader = mock(LeaseDefinitionLoader.class);
        MetricService metrics = mock(MetricService.class);
        when(metrics.counter(anyString(), any(String[].class)))
                .thenReturn(io.micrometer.core.instrument.Counter.builder("x")
                        .register(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        @SuppressWarnings("unchecked")
        ObjectProvider<LeaseHandler> handlers = mock(ObjectProvider.class);
        when(handlers.orderedStream()).thenReturn(java.util.stream.Stream.of(new TestHandler()));
        service = new ResourceLeaseService(mongoTemplate, loader, metrics, handlers);

        when(mongoTemplate.insert(any(ResourceLeaseHolderDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        UpdateResult one = mock(UpdateResult.class);
        when(one.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), any(Class.class)))
                .thenReturn(one);
    }

    private static LeaseRequest request(LeaseDefinition inline) {
        return LeaseRequest.builder()
                .type("test")
                .inline(inline)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .userId(USER)
                .holderRef("holder-ref")
                .pod("pod-1")
                .build();
    }

    private static LeaseDefinition def(Long global, Long tenant, Long project, Long user, long waitMs) {
        return new LeaseDefinition("tex", "tex2pdf", global, tenant, project, user, 60L, waitMs);
    }

    // ──────────────── funnel ────────────────

    @Test
    void acquire_pushesCountersNarrowToWide() {
        Lease lease = service.acquire(request(def(2L, 2L, 2L, 2L, 0L)));

        assertThat(lease.isNoop()).isFalse();
        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        // One findAndModify per level, narrow → wide (§3).
        verify(mongoTemplate, times(4))
                .findAndModify(queries.capture(), any(Update.class), any(), eq(ResourceLeaseCounterDocument.class));
        List<String> keys = queries.getAllValues().stream()
                .map(q -> String.valueOf(q.getQueryObject().get("_id")))
                .toList();
        assertThat(keys)
                .containsExactly(
                        "tex2pdf|user|acme/proj-1/alice",
                        "tex2pdf|project|acme/proj-1",
                        "tex2pdf|tenant|acme",
                        "tex2pdf|global|");
        // The holder ends up HELD.
        verify(mongoTemplate, atLeastOnce())
                .updateFirst(any(Query.class), any(Update.class), eq(ResourceLeaseHolderDocument.class));
    }

    @Test
    void acquire_levelsWithoutScopeOrCap_areSkipped() {
        LeaseRequest noProject = LeaseRequest.builder()
                .type("test")
                .inline(def(null, 2L, 2L, null, 0L))
                .tenantId(TENANT)
                .holderRef("holder-ref")
                .pod("pod-1")
                .build();

        service.acquire(noProject);

        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        // Project/user caps skipped (no scope), global cap absent, tenant cap applies.
        verify(mongoTemplate, times(1))
                .findAndModify(queries.capture(), any(Update.class), any(), eq(ResourceLeaseCounterDocument.class));
        assertThat(String.valueOf(queries.getValue().getQueryObject().get("_id")))
                .isEqualTo("tex2pdf|tenant|acme");
    }

    @Test
    void acquire_busyLevel_rollsBackAlreadyTakenCounters() {
        // Two levels (project, tenant): the first counter is taken, the
        // second is full — the attempt must roll the first one back.
        LeaseRequest two = LeaseRequest.builder()
                .type("test")
                .inline(def(null, 1L, 1L, null, 0L))
                .tenantId(TENANT)
                .projectId(PROJECT)
                .holderRef("holder-ref")
                .pod("pod-1")
                .build();
        when(mongoTemplate.findAndModify(
                        any(Query.class), any(Update.class), any(), eq(ResourceLeaseCounterDocument.class)))
                .thenAnswer(inv -> {
                    Query q = inv.getArgument(0);
                    return new ResourceLeaseCounterDocument(
                            String.valueOf(q.getQueryObject().get("_id")), 1);
                })
                .thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.acquire(two))
                .isInstanceOf(LeaseBusyException.class)
                .hasMessageContaining("tenant")
                .hasMessageContaining("full");

        // The first counter (project) was taken and must be given back —
        // one dec(-1) update against the counters collection.
        ArgumentCaptor<Update> dec = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, atLeastOnce())
                .updateFirst(any(Query.class), dec.capture(), eq(ResourceLeaseCounterDocument.class));
        assertThat(dec.getAllValues().stream().anyMatch(u -> u.toString().contains("-1")))
                .as("rollback decrements the already-acquired level")
                .isTrue();
    }

    @Test
    void acquire_busyWithinWaitBudget_retriesUntilDeadline() {
        when(mongoTemplate.findAndModify(
                        any(Query.class), any(Update.class), any(), eq(ResourceLeaseCounterDocument.class)))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenAnswer(inv -> new ResourceLeaseCounterDocument("key", 1));

        Lease lease = service.acquire(request(def(null, 1L, null, null, 5_000L)));

        assertThat(lease.isNoop()).isFalse();
        verify(mongoTemplate, times(3))
                .findAndModify(any(Query.class), any(Update.class), any(), eq(ResourceLeaseCounterDocument.class));
    }

    @Test
    void acquire_noCaps_returnsNoopLeaseWithoutMongo() {
        Lease lease = service.acquire(request(def(null, null, null, null, 0L)));

        assertThat(lease.isNoop()).isTrue();
        assertThatCode(lease::check).doesNotThrowAnyException();
        verify(mongoTemplate, never()).insert(any(ResourceLeaseHolderDocument.class));
    }

    @Test
    void acquire_unknownType_isRejected() {
        LeaseRequest badType = LeaseRequest.builder()
                .type("nobody-implements-this")
                .inline(def(1L, null, null, null, 0L))
                .tenantId(TENANT)
                .holderRef("holder-ref")
                .pod("pod-1")
                .build();

        assertThatThrownBy(() -> service.acquire(badType))
                .isInstanceOf(LeaseUnavailableException.class)
                .hasMessageContaining("no LeaseHandler bean registered");
    }

    @Test
    void acquire_missingNamedDefinition_isRejected() {
        when(loader.load(TENANT, PROJECT, "ghost")).thenReturn(Optional.empty());
        LeaseRequest named = LeaseRequest.builder()
                .type("test")
                .lease("ghost")
                .tenantId(TENANT)
                .projectId(PROJECT)
                .holderRef("holder-ref")
                .pod("pod-1")
                .build();

        assertThatThrownBy(() -> service.acquire(named))
                .isInstanceOf(LeaseUnavailableException.class)
                .hasMessageContaining("not defined in any layer");
    }

    // ──────────────── check / release ────────────────

    @Test
    void check_holderGone_throwsLeaseLost() {
        Lease lease = service.acquire(request(def(1L, null, null, null, 0L)));
        UpdateResult zero = mock(UpdateResult.class);
        when(zero.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ResourceLeaseHolderDocument.class)))
                .thenReturn(zero);

        assertThatThrownBy(lease::check).isInstanceOf(LeaseLostException.class);
    }

    @Test
    void release_holderAbsent_isIdempotent() {
        when(mongoTemplate.findAndRemove(any(Query.class), eq(ResourceLeaseHolderDocument.class)))
                .thenReturn(null);
        Lease lease = service.acquire(request(def(1L, null, null, null, 0L)));

        lease.release();
        lease.release();

        verify(mongoTemplate, times(2)).findAndRemove(any(Query.class), eq(ResourceLeaseHolderDocument.class));
    }

    private static final class TestHandler implements LeaseHandler {
        @Override
        public String type() {
            return "test";
        }

        @Override
        public void onRevoked(LeaseHolderInfo info) {}
    }
}
