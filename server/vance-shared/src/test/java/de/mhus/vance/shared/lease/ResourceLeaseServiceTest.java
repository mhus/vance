package de.mhus.vance.shared.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.metric.MetricService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The acquisition logic of {@link ResourceLeaseService} against
 * {@link FakeLeaseStore} — funnel order, first use of a counter, rollback
 * on busy and on store failure, lazy reap dispatch, check-at-use, release
 * and reconcile. The real Mongo semantics under concurrency (conditional
 * update, {@code $expr} reap filter on server time) are the E2E lane's
 * concern ({@code qa/ai-test/ResourceLeaseE2ETest}).
 */
class ResourceLeaseServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj-1";
    private static final String USER = "alice";

    private FakeLeaseStore store;
    private LeaseDefinitionLoader loader;
    private final List<LeaseHolderInfo> reapedToListener = new ArrayList<>();
    private ResourceLeaseService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new FakeLeaseStore();
        loader = mock(LeaseDefinitionLoader.class);
        MetricService metrics = mock(MetricService.class);
        when(metrics.counter(anyString(), any(String[].class)))
                .thenReturn(io.micrometer.core.instrument.Counter.builder("x")
                        .register(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        ObjectProvider<LeaseHandler> handlers = mock(ObjectProvider.class);
        when(handlers.orderedStream()).thenReturn(java.util.stream.Stream.of(new TestHandler()));
        ObjectProvider<LeaseRevocationListener> listeners = mock(ObjectProvider.class);
        when(listeners.getIfAvailable()).thenReturn(reapedToListener::addAll);
        service = new ResourceLeaseService(store.template, loader, metrics, handlers, listeners);
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
    void acquire_booksEveryLevel_userScopedAcrossProjects() {
        Lease lease = service.acquire(request(def(2L, 2L, 2L, 2L, 0L)));

        assertThat(lease.isNoop()).isFalse();
        ResourceLeaseHolderDocument holder = store.holders.get(lease.holderToken());
        // Narrow → wide, and the user level is per (tenant, user) — not per project.
        assertThat(holder.getLevels())
                .containsExactly(
                        "tex2pdf|user|acme/alice",
                        "tex2pdf|project|acme/proj-1",
                        "tex2pdf|tenant|acme",
                        "tex2pdf|global|");
        assertThat(holder.getState()).isEqualTo(ResourceLeaseService.STATE_HELD);
        holder.getLevels().forEach(key -> assertThat(store.count(key)).isEqualTo(1));
    }

    @Test
    void acquire_userCap_appliesAcrossTheTenantsProjects() {
        service.acquire(request(def(null, null, null, 1L, 0L)));
        LeaseRequest otherProject = LeaseRequest.builder()
                .type("test")
                .inline(def(null, null, null, 1L, 0L))
                .tenantId(TENANT)
                .projectId("proj-2")
                .userId(USER)
                .holderRef("holder-ref")
                .pod("pod-1")
                .build();

        assertThatThrownBy(() -> service.acquire(otherProject))
                .isInstanceOf(LeaseBusyException.class)
                .hasMessageContaining("'user'");
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

        Lease lease = service.acquire(noProject);

        assertThat(store.holders.get(lease.holderToken()).getLevels()).containsExactly("tex2pdf|tenant|acme");
    }

    @Test
    void acquire_firstUseOfACounter_createsItAtZeroInsteadOfReportingBusy() {
        assertThat(store.counters).isEmpty();

        service.acquire(request(def(null, 1L, null, null, 0L)));

        assertThat(store.count("tex2pdf|tenant|acme")).isEqualTo(1);
    }

    @Test
    void acquire_busyLevel_rollsBackAlreadyTakenCounters() {
        store.putCounter("tex2pdf|tenant|acme", 1, Instant.now());

        assertThatThrownBy(() -> service.acquire(request(def(null, 1L, 1L, null, 0L))))
                .isInstanceOf(LeaseBusyException.class)
                .hasMessageContaining("tenant")
                .hasMessageContaining("(1/1 held)");

        assertThat(store.count("tex2pdf|project|acme/proj-1"))
                .as("the project level taken first is given back")
                .isZero();
        assertThat(store.count("tex2pdf|tenant|acme")).isEqualTo(1);
        assertThat(store.holders).isEmpty();
    }

    @Test
    void acquire_busyWithinWaitBudget_succeedsOnceASlotFrees() throws Exception {
        Lease first = service.acquire(request(def(null, 1L, null, null, 0L)));
        Thread releaser = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            first.release();
        });

        Lease second = service.acquire(request(def(null, 1L, null, null, 5_000L)));
        releaser.join();

        assertThat(second.isNoop()).isFalse();
        assertThat(store.count("tex2pdf|tenant|acme")).isEqualTo(1);
    }

    @Test
    void acquire_storeFailureMidFunnel_rollsBackAndFailsClosed() {
        // The second level's $push fails: the first level's counter and the
        // half-booked holder must not survive the attempt.
        int[] pushes = {0};
        store.failWhen(op -> op.equals("holder.push") && ++pushes[0] == 2);

        assertThatThrownBy(() -> service.acquire(request(def(null, 1L, 1L, null, 0L))))
                .isInstanceOf(LeaseUnavailableException.class)
                .hasMessageContaining("injected store failure");

        assertThat(store.holders).isEmpty();
        assertThat(store.count("tex2pdf|project|acme/proj-1")).isZero();
        assertThat(store.count("tex2pdf|tenant|acme")).isZero();
    }

    @Test
    void acquire_holderReapedDuringAcquisition_isUnavailable() {
        // The HELD update finds no holder: a reaper removed it mid-funnel.
        store.failWhen(op -> {
            if (op.equals("holder.update")) store.holders.clear();
            return false;
        });

        assertThatThrownBy(() -> service.acquire(request(def(null, 1L, null, null, 0L))))
                .isInstanceOf(LeaseUnavailableException.class)
                .hasMessageContaining("reaped during acquisition");
    }

    @Test
    void acquire_lazyIdleReap_handsReapedHoldersToTheListener() {
        Lease stale = service.acquire(request(def(null, 1L, null, null, 0L)));
        store.expire(stale.holderToken());

        Lease fresh = service.acquire(request(def(null, 1L, null, null, 0L)));

        assertThat(fresh.holderToken()).isNotEqualTo(stale.holderToken());
        assertThat(reapedToListener).singleElement().satisfies(info -> {
            assertThat(info.token()).isEqualTo(stale.holderToken());
            assertThat(info.type()).isEqualTo("test");
        });
        assertThat(store.count("tex2pdf|tenant|acme")).isEqualTo(1);
    }

    @Test
    void acquire_noCaps_returnsNoopLeaseWithoutMongo() {
        Lease lease = service.acquire(request(def(null, null, null, null, 0L)));

        assertThat(lease.isNoop()).isTrue();
        assertThatCode(lease::check).doesNotThrowAnyException();
        verify(store.template, never())
                .upsert(any(Query.class), any(Update.class), eq(ResourceLeaseHolderDocument.class));
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

        assertThatThrownBy(() -> service.acquire(named("ghost")))
                .isInstanceOf(LeaseUnavailableException.class)
                .hasMessageContaining("not defined in any layer");
    }

    @Test
    void acquire_invalidLeaseName_isRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.acquire(named("../setting_forms/x")))
                .isInstanceOf(LeaseUnavailableException.class)
                .hasMessageContaining("invalid lease name");
        verify(loader, never()).load(any(), any(), any());
    }

    private static LeaseRequest named(String name) {
        return LeaseRequest.builder()
                .type("test")
                .lease(name)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .holderRef("holder-ref")
                .pod("pod-1")
                .build();
    }

    // ──────────────── check / payload / release ────────────────

    @Test
    void check_twiceWithinTheSameMillisecond_isNotLost() {
        // The fake's server clock is fixed: the second touch writes an
        // identical lastSeenAt — matched, not modified. Still held.
        Lease lease = service.acquire(request(def(1L, null, null, null, 0L)));

        assertThatCode(() -> {
                    lease.check();
                    lease.check();
                })
                .doesNotThrowAnyException();
    }

    @Test
    void updatePayload_identicalPayload_isNotLost() {
        Lease lease = service.acquire(request(def(1L, null, null, null, 0L)));

        lease.updatePayload(Map.of("jobId", "j-1"));
        assertThatCode(() -> lease.updatePayload(Map.of("jobId", "j-1"))).doesNotThrowAnyException();
        assertThat(store.holders.get(lease.holderToken()).getPayload()).containsEntry("jobId", "j-1");
    }

    @Test
    void check_holderGone_throwsLeaseLost() {
        Lease lease = service.acquire(request(def(1L, null, null, null, 0L)));
        store.holders.clear();

        assertThatThrownBy(lease::check).isInstanceOf(LeaseLostException.class);
    }

    @Test
    void release_freesTheSlots_andIsIdempotent() {
        Lease lease = service.acquire(request(def(1L, 1L, null, null, 0L)));

        lease.release();
        lease.release();

        assertThat(store.holders).isEmpty();
        assertThat(store.count("tex2pdf|global|")).isZero();
        assertThat(store.count("tex2pdf|tenant|acme")).isZero();
    }

    // ──────────────── reconcile ────────────────

    @Test
    void reconcile_correctsDriftFromTheHolders() {
        Lease lease = service.acquire(request(def(null, 5L, null, null, 0L)));
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        store.putCounter("tex2pdf|tenant|acme", 3, old);

        long corrected = service.reconcile(old.plusSeconds(60));

        assertThat(corrected).isEqualTo(1);
        assertThat(store.count("tex2pdf|tenant|acme")).isEqualTo(1);
        assertThat(lease.holderToken()).isNotBlank();
    }

    @Test
    void reconcile_removesCountersNoHolderBooks() {
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        store.putCounter("tex2pdf|user|acme/bob", 0, old);
        store.putCounter("tex2pdf|project|acme/gone", 2, old);

        long corrected = service.reconcile(old.plusSeconds(60));

        assertThat(store.counters).isEmpty();
        assertThat(corrected).as("only the drifted one counts as a correction").isEqualTo(1);
    }

    @Test
    void reconcile_leavesRecentlyWrittenCountersAlone() {
        Instant recent = Instant.parse("2026-01-01T00:00:00Z");
        store.putCounter("tex2pdf|tenant|acme", 4, recent);

        long corrected = service.reconcile(recent.minusSeconds(60));

        assertThat(corrected).isZero();
        assertThat(store.count("tex2pdf|tenant|acme")).isEqualTo(4);
    }

    // ──────────────── operator surface ────────────────

    @Test
    void revoke_removesTheHolderAndGivesItsSlotsBack() {
        Lease lease = service.acquire(request(def(1L, 1L, null, null, 0L)));

        Optional<ResourceLeaseHolderDocument> revoked = service.revoke(lease.holderToken(), null);

        assertThat(revoked).map(ResourceLeaseHolderDocument::getToken).contains(lease.holderToken());
        assertThat(store.holders).isEmpty();
        assertThat(store.count("tex2pdf|global|")).isZero();
        assertThat(store.count("tex2pdf|tenant|acme")).isZero();
        // The holder learns it at its next use.
        assertThatThrownBy(lease::check).isInstanceOf(LeaseLostException.class);
    }

    @Test
    void revoke_withTenant_doesNotReachAnotherTenantsHolder() {
        Lease lease = service.acquire(request(def(1L, null, null, null, 0L)));

        assertThat(service.revoke(lease.holderToken(), "other-tenant")).isEmpty();

        assertThat(store.holders).containsKey(lease.holderToken());
        assertThat(store.count("tex2pdf|global|")).isEqualTo(1);
        assertThat(service.revoke(lease.holderToken(), TENANT)).isPresent();
    }

    @Test
    void revoke_unknownToken_isEmptyAndTouchesNoCounter() {
        service.acquire(request(def(1L, null, null, null, 0L)));

        assertThat(service.revoke("rl-unknown", null)).isEmpty();
        assertThat(store.count("tex2pdf|global|")).isEqualTo(1);
    }

    @Test
    void listHolders_byTenant_filtersOnTheTenant() {
        service.acquire(request(def(5L, null, null, null, 0L)));
        service.acquire(LeaseRequest.builder()
                .type("test")
                .inline(def(5L, null, null, null, 0L))
                .tenantId("other")
                .holderRef("holder-ref")
                .pod("pod-1")
                .build());

        assertThat(service.listHolders()).hasSize(2);
        assertThat(service.listHolders(TENANT))
                .extracting(ResourceLeaseHolderDocument::getTenantId)
                .containsExactly(TENANT);
    }

    @Test
    void listCounters_byTenant_leavesTheGlobalLevelAndOtherTenantsOut() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        store.putCounter("tex2pdf|global|", 3, at);
        store.putCounter("tex2pdf|tenant|acme", 2, at);
        store.putCounter("tex2pdf|project|acme/proj-1", 1, at);
        store.putCounter("tex2pdf|user|acme/alice", 1, at);
        store.putCounter("tex2pdf|tenant|acme2", 1, at);
        store.putCounter("tex2pdf|project|acme2/proj-1", 1, at);

        assertThat(service.listCounters()).hasSize(6);
        assertThat(service.listCounters(TENANT))
                .extracting(LeaseCounter::key)
                .containsExactlyInAnyOrder(
                        "tex2pdf|tenant|acme", "tex2pdf|project|acme/proj-1", "tex2pdf|user|acme/alice");
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
