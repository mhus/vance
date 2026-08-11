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

import de.mhus.vance.shared.schema.SchemaMigrationService.RegisteredMigration;
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
                .thenReturn(List.of(), List.of(applied(FIRST)));
        acquireSucceeds();

        SchemaMigrationReport report =
                service(entry(FIRST, First.class), entry(SECOND, Second.class)).runPending();

        assertThat(EXECUTED).containsExactly(SECOND);
        assertThat(report.applied()).containsExactly(SECOND);
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
                .thenReturn(List.of(), List.of(applied(FIRST)));
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

    private SchemaMigrationService service(RegisteredMigration... registry) {
        return new SchemaMigrationService(mongoTemplate, lockStore, properties, List.of(registry));
    }

    private static RegisteredMigration entry(String id, Class<? extends SchemaMigration> type) {
        return new RegisteredMigration(id, type);
    }

    private void markers(SchemaMigrationDocument... docs) {
        when(mongoTemplate.findAll(SchemaMigrationDocument.class)).thenReturn(List.of(docs));
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
