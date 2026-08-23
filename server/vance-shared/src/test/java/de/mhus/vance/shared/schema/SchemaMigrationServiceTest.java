package de.mhus.vance.shared.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.schema.SchemaMigrationSource.Registered;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Decision logic of the runner: which migrations run, in which order, what gets
 * marked, and when the boot fails. The lock store is mocked — its two
 * {@code findAndModify} CAS wrappers carry no branching worth a unit test.
 *
 * <p>Migrations are instantiated reflectively from the registry, so the fixtures
 * below are public no-arg classes recording into a static list.
 */
class SchemaMigrationServiceTest {

    /** Ids recorded by the fixture migrations, in execution order. */
    static final List<String> EXECUTED = new ArrayList<>();

    /** Contexts the fixtures were handed. */
    static final List<SchemaMigrationContext> CONTEXTS = new ArrayList<>();

    /** Stands for "this database has been seen before"; sorts below everything. */
    private static final String ANCHOR = "2026-07-01_001";

    private static final String FIRST = "2026-08-01_001";
    private static final String SECOND = "2026-08-05_001";

    private MongoTemplate mongoTemplate;
    private SchemaMigrationLockStore lockStore;
    private SchemaMigrationProperties properties;

    @BeforeEach
    void setUp() {
        EXECUTED.clear();
        CONTEXTS.clear();
        mongoTemplate = mock(MongoTemplate.class);
        lockStore = mock(SchemaMigrationLockStore.class);
        properties = new SchemaMigrationProperties();
        // Keep the waiting tests in the millisecond range.
        properties.setLockWait(Duration.ofMillis(20));
        properties.setLockPollInterval(Duration.ofMillis(1));
    }

    // ─── happy path ─────────────────────────────────────────────────

    @Test
    void runPending_appliesEverythingAboveTheCurrentVersion_inRegistryOrder() {
        markers();
        acquireSucceeds();

        SchemaMigrationReport report = service(entry(FIRST, First.class), entry(SECOND, Second.class))
                .runPending();

        assertThat(EXECUTED).containsExactly(FIRST, SECOND);
        assertThat(report.applied()).containsExactly(FIRST, SECOND);
        assertThat(report.version()).isEqualTo(SECOND);
        assertThat(report.declared()).isEqualTo(2);
        assertThat(report.noop()).isFalse();
    }

    @Test
    void runPending_writesAppliedMarker_withIdentityAndImplementingClass() {
        markers();
        acquireSucceeds();

        service(entry(FIRST, First.class)).runPending();

        SchemaMigrationDocument marker = savedMarkers().get(0);
        assertThat(marker.getId()).isEqualTo(FIRST);
        assertThat(marker.getStatus()).isEqualTo(SchemaMigrationState.APPLIED);
        assertThat(marker.getMigrationClass()).isEqualTo(First.class.getName());
        assertThat(marker.getAppliedAt()).isNotNull();
        assertThat(marker.getAppliedByPod()).isNotBlank();
        assertThat(marker.getError()).isNull();
    }

    @Test
    void runPending_handsTheMongoTemplateAndIdToTheMigration() {
        markers();
        acquireSucceeds();

        service(entry(FIRST, First.class)).runPending();

        assertThat(CONTEXTS).hasSize(1);
        assertThat(CONTEXTS.get(0).mongoTemplate()).isSameAs(mongoTemplate);
        assertThat(CONTEXTS.get(0).migrationId()).isEqualTo(FIRST);
        assertThat(CONTEXTS.get(0).ownerId()).isNotBlank();
    }

    // ─── the linear version model ───────────────────────────────────

    @Test
    void runPending_skipsWhatIsAtOrBelowTheCurrentVersion() {
        markers(applied(FIRST));
        acquireSucceeds();

        SchemaMigrationReport report =
                service(entry(FIRST, First.class), entry(SECOND, Second.class)).runPending();

        assertThat(EXECUTED).containsExactly(SECOND);
        assertThat(report.version()).isEqualTo(SECOND);
    }

    @Test
    void runPending_retriesAMigrationWhoseMarkerFailed() {
        // A FAILED marker does not raise the version, so the migration is pending again.
        markers(failed(FIRST));
        acquireSucceeds();

        service(entry(FIRST, First.class)).runPending();

        assertThat(EXECUTED).containsExactly(FIRST);
    }

    @Test
    void runPending_doesNotRunAMigrationRegisteredBelowTheCurrentVersion() {
        // The merge case: a migration arrives with a date older than what the
        // database already applied. Linear model skips it (and warns).
        markers(applied(SECOND));

        SchemaMigrationReport report =
                service(entry(FIRST, First.class), entry(SECOND, Second.class)).runPending();

        assertThat(EXECUTED).isEmpty();
        assertThat(report.noop()).isTrue();
        assertThat(report.version()).isEqualTo(SECOND);
    }

    @Test
    void runPending_treatsAnUnknownAppliedMarkerAsTheCurrentVersion() {
        // Rollback: a newer build migrated past us. Warn, do not re-run, do not fail.
        markers(applied("2026-12-01_001"));

        SchemaMigrationReport report = service(entry(FIRST, First.class)).runPending();

        assertThat(report.noop()).isTrue();
        assertThat(report.version()).isEqualTo("2026-12-01_001");
        assertThat(EXECUTED).isEmpty();
    }

    @Test
    void runPending_isNoop_andNeverTakesTheLock_whenAtTheRequiredVersion() {
        markers(applied(FIRST));

        SchemaMigrationReport report = service(entry(FIRST, First.class)).runPending();

        assertThat(report.noop()).isTrue();
        assertThat(EXECUTED).isEmpty();
        // Matters for the manual Ops trigger: a no-op run must not touch the lock.
        verifyNoInteractions(lockStore);
    }

    @Test
    void runPending_reReadsMarkersUnderTheLock_andSkipsWhatAnotherPodApplied() {
        // First read: nothing applied. Second read (under the lock): the pod that
        // held it before us finished the first migration.
        when(mongoTemplate.findAll(SchemaMigrationDocument.class))
                .thenReturn(List.of(baselined(ANCHOR)), List.of(baselined(ANCHOR), applied(FIRST)));
        acquireSucceeds();

        SchemaMigrationReport report =
                service(entry(FIRST, First.class), entry(SECOND, Second.class)).runPending();

        assertThat(EXECUTED).containsExactly(SECOND);
        assertThat(report.applied()).containsExactly(SECOND);
    }

    // ─── baseline (a database nobody has stamped yet) ───────────────

    @Test
    void runPending_baselinesWithoutRunning_whenTheDatabaseHasNoMarkerAtAll() {
        // No marker means "new database": it was written by the current code, so
        // historical transforms have nothing to do there.
        noMarkers();

        SchemaMigrationReport report =
                service(entry(FIRST, First.class), entry(SECOND, Second.class)).runPending();

        assertThat(EXECUTED).isEmpty();
        assertThat(report.baselined()).isTrue();
        assertThat(report.applied()).isEmpty();
        assertThat(report.version()).isEqualTo(SECOND);
        assertThat(report.noop()).isFalse();
    }

    @Test
    void runPending_baseline_marksEveryRegistered_asBaselined() {
        // Not APPLIED: the history must not claim work that never happened. And
        // every entry gets a marker, otherwise the ones below the version would be
        // reported as skipped on the next boot.
        noMarkers();

        service(entry(FIRST, First.class), entry(SECOND, Second.class)).runPending();

        assertThat(savedMarkers()).hasSize(2);
        assertThat(savedMarkers()).allSatisfy(marker ->
                assertThat(marker.getStatus()).isEqualTo(SchemaMigrationState.BASELINED));
        assertThat(savedMarkers()).extracting(SchemaMigrationDocument::getId)
                .containsExactly(FIRST, SECOND);
    }

    @Test
    void runPending_baseline_neverTakesTheLock() {
        // Concurrent baselining writes byte-identical markers keyed by _id.
        noMarkers();

        service(entry(FIRST, First.class)).runPending();

        verifyNoInteractions(lockStore);
    }

    @Test
    void runPending_baseline_stillRunsAMigrationThatOptedIn() {
        // "No marker" also describes a database restored from before the
        // anchor release. For a migration that is the only writer of a value
        // the running code now reads differently, being skipped there is not
        // recoverable — no later boot re-tries it.
        noMarkers();
        acquireSucceeds();

        SchemaMigrationReport report = service(
                entry(FIRST, First.class),
                onBaseline(SECOND, Second.class)).runPending();

        assertThat(EXECUTED).containsExactly(SECOND);
        assertThat(report.baselined()).isTrue();
        assertThat(report.applied()).containsExactly(SECOND);
        assertThat(report.version()).isEqualTo(SECOND);
    }

    @Test
    void runPending_baseline_stampsOnlyTheSkippedOnes() {
        // The one that ran must keep its APPLIED marker — stamping it
        // BASELINED afterwards would claim work never happened.
        noMarkers();
        acquireSucceeds();

        service(entry(FIRST, First.class), onBaseline(SECOND, Second.class)).runPending();

        assertThat(savedMarkers())
                .filteredOn(m -> SECOND.equals(m.getId()))
                .allSatisfy(m -> assertThat(m.getStatus())
                        .isEqualTo(SchemaMigrationState.APPLIED));
        assertThat(savedMarkers())
                .filteredOn(m -> FIRST.equals(m.getId()))
                .allSatisfy(m -> assertThat(m.getStatus())
                        .isEqualTo(SchemaMigrationState.BASELINED));
    }

    @Test
    void runPending_baseline_takesTheLock_onlyWhenSomethingActuallyRuns() {
        // The "identical markers, no lock needed" argument covers stamping,
        // not a migration that touches data.
        noMarkers();
        acquireSucceeds();

        service(onBaseline(FIRST, First.class)).runPending();

        verify(lockStore).tryAcquire(anyString(), any(), any());
    }

    @Test
    void runPending_doesNotBaseline_whenOnlyAFailedMarkerExists() {
        // A failed marker is proof the database has been seen before — baselining
        // it would silently drop the migration that is trying to run.
        when(mongoTemplate.findAll(SchemaMigrationDocument.class))
                .thenReturn(List.of(failed(FIRST)));
        acquireSucceeds();

        SchemaMigrationReport report = service(entry(FIRST, First.class)).runPending();

        assertThat(report.baselined()).isFalse();
        assertThat(EXECUTED).containsExactly(FIRST);
    }

    // ─── failure handling ───────────────────────────────────────────

    @Test
    void runPending_writesFailedMarker_abortsRemaining_andReleasesTheLock() {
        markers();
        acquireSucceeds();

        SchemaMigrationService service = service(entry(FIRST, Boom.class), entry(SECOND, Second.class));

        assertThatThrownBy(service::runPending)
                .isInstanceOf(SchemaMigrationException.class)
                .hasMessageContaining(FIRST)
                .hasRootCauseMessage("index missing");
        assertThat(EXECUTED).containsExactly(FIRST);

        SchemaMigrationDocument marker = savedMarkers().get(0);
        assertThat(marker.getStatus()).isEqualTo(SchemaMigrationState.FAILED);
        assertThat(marker.getError()).contains("IllegalStateException").contains("index missing");
        verify(lockStore).release(anyString());
    }

    @Test
    void runPending_aborts_whenTheLeaseIsLostBetweenMigrations() {
        markers();
        when(lockStore.tryAcquire(anyString(), any(), any())).thenReturn(true);
        when(lockStore.renew(anyString(), any())).thenReturn(false);

        SchemaMigrationService service = service(entry(FIRST, First.class), entry(SECOND, Second.class));

        assertThatThrownBy(service::runPending)
                .isInstanceOf(SchemaMigrationException.class)
                .hasMessageContaining("Lost the schema-migration lock");
        // The first one completed and is marked; the second never started.
        assertThat(EXECUTED).containsExactly(FIRST);
        assertThat(savedMarkers()).hasSize(1);
        assertThat(savedMarkers().get(0).getStatus()).isEqualTo(SchemaMigrationState.APPLIED);
    }

    // ─── new against new ────────────────────────────────────────────

    @Test
    void runPending_reportsAppliedByOtherPod_whenTheHolderFinishesWhileWeWait() {
        when(mongoTemplate.findAll(SchemaMigrationDocument.class))
                .thenReturn(List.of(baselined(ANCHOR)), List.of(baselined(ANCHOR), applied(FIRST)));
        when(lockStore.tryAcquire(anyString(), any(), any())).thenReturn(false);

        SchemaMigrationReport report = service(entry(FIRST, First.class)).runPending();

        assertThat(report.appliedByOtherPod()).isTrue();
        assertThat(report.applied()).isEmpty();
        assertThat(report.version()).isEqualTo(FIRST);
        assertThat(EXECUTED).isEmpty();
        verify(lockStore, never()).release(anyString());
    }

    @Test
    void runPending_throws_whenWaitingForTheLockTimesOut() {
        // The holder neither releases nor finishes: we cannot confirm the database
        // reached the required version, so the boot must fail.
        markers();
        when(lockStore.tryAcquire(anyString(), any(), any())).thenReturn(false);

        SchemaMigrationService service = service(entry(FIRST, First.class));

        assertThatThrownBy(service::runPending)
                .isInstanceOf(SchemaMigrationException.class)
                .hasMessageContaining("Timed out")
                .hasMessageContaining(FIRST);
        assertThat(EXECUTED).isEmpty();
    }

    // ─── boot gate ──────────────────────────────────────────────────

    @Test
    void migrateOnBoot_doesNothing_whenDisabled() {
        properties.setMigrateOnBoot(false);

        service(entry(FIRST, First.class)).migrateOnBoot();

        assertThat(EXECUTED).isEmpty();
        verifyNoInteractions(mongoTemplate);
        verifyNoInteractions(lockStore);
    }

    @Test
    void migrateOnBoot_appliesPending_whenEnabled() {
        markers();
        acquireSucceeds();

        service(entry(FIRST, First.class)).migrateOnBoot();

        assertThat(EXECUTED).containsExactly(FIRST);
    }

    // ─── helpers ────────────────────────────────────────────────────

    private SchemaMigrationService service(Registered... registry) {
        // One source is the normal case; what happens with none, with an
        // empty one and with a clashing id is covered by
        // SchemaMigrationSourceMergeTest.
        return new SchemaMigrationService(mongoTemplate, lockStore, properties,
                List.of(source("test", List.of(registry))));
    }

    private static SchemaMigrationSource source(String name, List<Registered> entries) {
        return new SchemaMigrationSource() {
            @Override
            public List<Registered> migrations() {
                return entries;
            }

            @Override
            public String sourceName() {
                return name;
            }
        };
    }

    private static Registered entry(String id, Class<? extends SchemaMigration> type) {
        return new Registered(id, type);
    }

    /** A registry line that refuses to be baselined away. */
    private static Registered onBaseline(String id, Class<? extends SchemaMigration> type) {
        return new Registered(id, type, /*runOnBaseline*/ true);
    }

    /**
     * Stubs the marker collection. The no-arg form is <em>not</em> empty: it
     * carries an anchor marker, because a genuinely empty collection means "new
     * database" and takes the baseline short-circuit. Use {@link #noMarkers()} for
     * that case. The anchor sorts below {@link #FIRST}, so everything registered
     * is pending — which is what the run tests want.
     */
    private void markers(SchemaMigrationDocument... docs) {
        List<SchemaMigrationDocument> all = new ArrayList<>();
        all.add(baselined(ANCHOR));
        all.addAll(List.of(docs));
        when(mongoTemplate.findAll(SchemaMigrationDocument.class)).thenReturn(all);
    }

    /** A database this framework has never seen. */
    private void noMarkers() {
        when(mongoTemplate.findAll(SchemaMigrationDocument.class)).thenReturn(List.of());
    }

    private void acquireSucceeds() {
        when(lockStore.tryAcquire(anyString(), any(), any())).thenReturn(true);
        when(lockStore.renew(anyString(), any())).thenReturn(true);
    }

    private List<SchemaMigrationDocument> savedMarkers() {
        ArgumentCaptor<SchemaMigrationDocument> captor =
                ArgumentCaptor.forClass(SchemaMigrationDocument.class);
        verify(mongoTemplate, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private static SchemaMigrationDocument applied(String id) {
        return marker(id, SchemaMigrationState.APPLIED);
    }

    private static SchemaMigrationDocument failed(String id) {
        return marker(id, SchemaMigrationState.FAILED);
    }

    private static SchemaMigrationDocument baselined(String id) {
        return marker(id, SchemaMigrationState.BASELINED);
    }

    private static SchemaMigrationDocument marker(String id, SchemaMigrationState state) {
        return SchemaMigrationDocument.builder()
                .id(id)
                .status(state)
                .appliedAt(Instant.parse("2026-08-01T10:00:00Z"))
                .build();
    }

    // ─── fixtures (public no-arg, like a real migration) ─────────────

    /** Records that it ran. */
    public static final class First implements SchemaMigration {
        @Override
        public void up(SchemaMigrationContext context) {
            EXECUTED.add(context.migrationId());
            CONTEXTS.add(context);
        }
    }

    /** Second fixture — distinct class so the marker's class name is checkable. */
    public static final class Second implements SchemaMigration {
        @Override
        public void up(SchemaMigrationContext context) {
            EXECUTED.add(context.migrationId());
            CONTEXTS.add(context);
        }
    }

    /** Runs, then fails. */
    public static final class Boom implements SchemaMigration {
        @Override
        public void up(SchemaMigrationContext context) {
            EXECUTED.add(context.migrationId());
            throw new IllegalStateException("index missing");
        }
    }
}
