package de.mhus.vance.shared.schema;

import de.mhus.vance.shared.schema.migrations.Migrator_2026_08_12_001_Baseline;
import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Brings the database to the version this build requires, then lets the rest of
 * the context start. Design and rationale: {@code planning/schema-migration.md}.
 *
 * <h2>Version model</h2>
 * Linear and derived, never hand-maintained: the <b>required</b> version is the
 * last entry of {@link #MIGRATIONS}, the <b>current</b> version is the highest
 * id carrying an {@code APPLIED} or {@code BASELINED} marker, and pending is
 * everything in between. Ids are {@code YYYY-MM-DD_NNN} (see {@link #MIGRATIONS}),
 * so lexicographic order is chronological order.
 *
 * <p>A database with <b>no marker at all</b> is taken to be new and is
 * <b>baselined</b> — stamped at the current version without running anything, on
 * the grounds that a database written by the current code has nothing historical
 * to transform. This is why the registry ships an anchor before the first real
 * migration exists; see {@link #baseline()}.
 *
 * <h2>Ordering</h2>
 * {@link SchemaMigrationOrderingPostProcessor} makes every Mongo repository bean
 * depend on this one, and this one depends on nothing above {@code MongoTemplate}.
 * The migrator therefore runs <em>between</em> the Mongo infrastructure and the
 * repository layer: no service can read a shape that has not been migrated yet,
 * and nobody has to sprinkle {@code @DependsOn} anywhere.
 *
 * <h2>What is handled here, and what is not</h2>
 * <ul>
 *   <li><b>New against new</b> (several pods booting at once): the
 *       {@link SchemaMigrationLockStore} lease plus the per-migration marker.</li>
 *   <li><b>Old against new</b> (an old pod still writing the old shape) is
 *       explicitly <em>not</em> handled. Quiescing old writers is the external
 *       deployment's job; a non-additive migration shipped as a plain rolling
 *       update loses data no matter what this class does (§1, §4).</li>
 *   <li><b>Fail-fast is the compatibility gate</b> (§3): anything that leaves
 *       the database at an unknown version throws out of {@code @PostConstruct},
 *       which fails the context instead of serving requests against a shape the
 *       code does not understand.</li>
 * </ul>
 */
@Service(SchemaMigrationService.BEAN_NAME)
@Slf4j
public class SchemaMigrationService {

    /** Fixed so {@link SchemaMigrationOrderingPostProcessor} can name it. */
    public static final String BEAN_NAME = "schemaMigrationService";

    /**
     * Every migration this build knows, in ascending id order.
     *
     * <p><b>To add one:</b> write a {@link SchemaMigration} implementation (a
     * plain public class, no bean) and append one line here. The id becomes the
     * {@code _id} of the marker document and is never changed afterwards — a
     * renamed id re-runs the migration.
     *
     * <p><b>Id format {@code YYYY-MM-DD_NNN}</b>: ISO date of the release plus a
     * three-digit counter, counting from {@code _001} within the day. The counter
     * is mandatory, not "only when there are two on one day" — a single id shape
     * means a single comparison rule, and zero padding keeps lexicographic order
     * equal to numeric order ({@code _002} before {@code _010}).
     *
     * <pre>
     * private static final List&lt;RegisteredMigration&gt; MIGRATIONS = List.of(
     *         new RegisteredMigration("2026-08-01_001", Migrator_2026_08_01_001_NewSecretSettingsType.class),
     *         new RegisteredMigration("2026-08-01_002", Migrator_2026_08_01_002_DropLegacyTrashPaths.class));
     * </pre>
     *
     * <p>Integrity of this list — unique, ascending, instantiable — is asserted by
     * {@code SchemaMigrationRegistryTest}, not re-checked on every boot.
     *
     * <p>The only entry today is the anchor
     * {@link Migrator_2026_08_12_001_Baseline}: it does nothing and exists so a
     * database is "known" before the first real migration ever ships — see its
     * class comment. The three hand-written {@code @PostConstruct} backfills in
     * vance-brain still run the old way ({@code planning/schema-migration.md} §3)
     * — moving them over is a separate track.
     */
    static final List<RegisteredMigration> MIGRATIONS = List.of(
            new RegisteredMigration("2026-08-12_001", Migrator_2026_08_12_001_Baseline.class));

    /** One registry line: the id that becomes the marker, and the class to run. */
    record RegisteredMigration(String id, Class<? extends SchemaMigration> type) {}

    private final MongoTemplate mongoTemplate;
    private final SchemaMigrationLockStore lockStore;
    private final SchemaMigrationProperties properties;

    /** Validated registry, id → class, in run order. */
    private final Map<String, Class<? extends SchemaMigration>> migrations;

    /** Lease-holder and marker identity of this process. */
    private final String ownerId = resolveOwnerId();

    // Explicit: the second constructor exists for tests, so Spring cannot pick by
    // arity and would look for a default constructor.
    @Autowired
    public SchemaMigrationService(
            MongoTemplate mongoTemplate,
            SchemaMigrationLockStore lockStore,
            SchemaMigrationProperties properties) {
        this(mongoTemplate, lockStore, properties, MIGRATIONS);
    }

    SchemaMigrationService(
            MongoTemplate mongoTemplate,
            SchemaMigrationLockStore lockStore,
            SchemaMigrationProperties properties,
            List<RegisteredMigration> registry) {
        this.mongoTemplate = mongoTemplate;
        this.lockStore = lockStore;
        this.properties = properties;
        this.migrations = index(registry);
    }

    /**
     * Ordered id → class map. Pure translation: the registry's integrity (unique,
     * ascending, instantiable ids) is a build-time property asserted by
     * {@code SchemaMigrationRegistryTest}, so there is nothing to validate at
     * runtime on every boot.
     */
    private static Map<String, Class<? extends SchemaMigration>> index(
            List<RegisteredMigration> registry) {
        Map<String, Class<? extends SchemaMigration>> byId = new LinkedHashMap<>();
        for (RegisteredMigration entry : registry) {
            byId.put(entry.id(), entry.type());
        }
        return byId;
    }

    /**
     * Runs during bean creation, i.e. before the repository layer
     * ({@link SchemaMigrationOrderingPostProcessor}). Throwing here fails the
     * context — brain never becomes ready against a database it cannot handle.
     */
    @PostConstruct
    void migrateOnBoot() {
        if (!properties.isMigrateOnBoot()) {
            log.warn("Schema migrations: auto-run disabled (vance.schema.migrate-on-boot=false) — "
                    + "the database is expected to be migrated externally");
            return;
        }
        SchemaMigrationReport report = runPending();
        if (report.noop()) {
            log.debug("Schema migrations: nothing pending, database at version '{}' ({} declared)",
                    report.version(), report.declared());
        } else if (report.baselined()) {
            // baseline() already logged the details at WARN.
            log.info("Schema migrations: new database baselined at version '{}'", report.version());
        } else if (report.appliedByOtherPod()) {
            log.info("Schema migrations: brought to version '{}' by another pod", report.version());
        } else {
            log.info("Schema migrations: applied {} — database now at version '{}'",
                    report.applied(), report.version());
        }
    }

    // ─── the run ────────────────────────────────────────────────────

    /**
     * Applies every registered migration above the database's current version.
     *
     * <p>Also usable as a manual Ops trigger: a run with nothing pending touches
     * neither the lock nor any collection.
     *
     * @throws SchemaMigrationException when the database cannot be brought to
     *         the required version — a migration failed, the lease was lost, or
     *         another pod did not finish in time
     */
    public SchemaMigrationReport runPending() {
        Map<String, SchemaMigrationDocument> markers = loadMarkers();
        if (markers.isEmpty() && !migrations.isEmpty()) {
            return baseline();
        }
        String current = currentVersion(markers);
        warnAboutUnknown(markers);
        warnAboutSkipped(markers, current);

        List<String> pending = pendingAbove(current);
        if (pending.isEmpty()) {
            return report(List.of(), current, false);
        }
        log.info("Schema migrations: database at version '{}', {} pending: {}",
                current, pending.size(), pending);
        return runLocked(pending);
    }

    /**
     * Stamps every registered migration as {@link SchemaMigrationState#BASELINED}
     * without running it, and thereby puts the database at the current version.
     *
     * <p>Triggered by "no marker at all". A database that has never been seen by
     * this framework is taken to be a <b>new</b> database: its collections were
     * written by the current code, so historical transforms have nothing to do
     * there. Running them would be pointless work that grows with every release.
     *
     * <p><b>The one case this gets wrong</b> is a database that predates the
     * framework and does hold old-shaped data — from here it looks exactly like a
     * new one and gets baselined instead of migrated. Harmless as long as the
     * anchor {@link Migrator_2026_08_12_001_Baseline} is the only thing baselined
     * away, which is why the anchor ships <em>before</em> the first real
     * migration: afterwards every database is known, and nothing is ever silently
     * skipped again. See {@code specification/schema-migration.md} §2.2.
     *
     * <p>No lease is taken: two pods baselining a fresh database concurrently
     * write byte-identical markers keyed by {@code _id}.
     */
    private SchemaMigrationReport baseline() {
        List<String> ids = List.copyOf(migrations.keySet());
        for (String id : ids) {
            writeMarker(id, SchemaMigrationState.BASELINED, 0L, null);
        }
        String version = ids.get(ids.size() - 1);
        log.warn("Schema migrations: no marker found — treating this as a new database and "
                        + "baselining it at version '{}' without running anything ({} migration(s) "
                        + "marked BASELINED). If this database actually predates the migration "
                        + "framework, its data has NOT been migrated.",
                version, ids.size());
        return new SchemaMigrationReport(List.of(), migrations.size(), version, false, true);
    }

    /**
     * Acquires the lease and applies the pending set. While another pod holds it
     * we wait — it may finish the work for us, which is the normal scale-up
     * case. A lease we never get is a boot failure: we cannot confirm the
     * database reached the required version.
     */
    private SchemaMigrationReport runLocked(List<String> pending) {
        Instant deadline = Instant.now().plus(properties.getLockWait());
        while (true) {
            Instant now = Instant.now();
            if (lockStore.tryAcquire(ownerId, now, now.plus(properties.getLockTtl()))) {
                try {
                    return applyAll(pending);
                } finally {
                    lockStore.release(ownerId);
                }
            }
            String current = currentVersion(loadMarkers());
            if (pendingAbove(current).isEmpty()) {
                log.info("Schema migrations: applied by another pod while we waited for the lock");
                return report(List.of(), current, true);
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new SchemaMigrationException("Timed out after " + properties.getLockWait()
                        + " waiting for the schema-migration lock; database still at version '"
                        + current + "', pending: " + pendingAbove(current)
                        + ". Another pod holds the lock and has not finished.");
            }
            sleep(properties.getLockPollInterval());
        }
    }

    /** Runs the pending ids in order, holding the lease throughout. */
    private SchemaMigrationReport applyAll(List<String> planned) {
        // Re-read under the lock: the pod that held it before us may have applied
        // part of the set between our first read and the acquisition.
        String current = currentVersion(loadMarkers());
        List<String> applied = new ArrayList<>();
        for (String id : planned) {
            if (id.compareTo(current) <= 0) {
                log.debug("Schema migration '{}': covered by version '{}' already, skipping",
                        id, current);
                continue;
            }
            // The lease is renewed between migrations, not before the first one
            // (we just acquired it). Losing it means another pod took over.
            if (!applied.isEmpty()) {
                renewLease();
            }
            apply(id, instantiate(id));
            applied.add(id);
            current = id;
        }
        return report(List.copyOf(applied), current, false);
    }

    private void renewLease() {
        if (!lockStore.renew(ownerId, Instant.now().plus(properties.getLockTtl()))) {
            throw new SchemaMigrationException(
                    "Lost the schema-migration lock (the lease expired and another pod took it). "
                            + "Aborting rather than migrating in parallel; raise "
                            + "vance.schema.lock-ttl if a single migration legitimately runs this long.");
        }
    }

    private void apply(String id, SchemaMigration migration) {
        log.info("Schema migration '{}' starting ({})", id, migration.getClass().getSimpleName());
        long startedNanos = System.nanoTime();
        try {
            migration.up(new SchemaMigrationContext(mongoTemplate, id, ownerId));
        } catch (RuntimeException e) {
            long durationMs = elapsedMs(startedNanos);
            // Best-effort breadcrumb: the original failure must survive even when
            // the marker write fails too.
            try {
                writeMarker(id, SchemaMigrationState.FAILED, durationMs, describe(e));
            } catch (RuntimeException markerFailure) {
                log.error("Schema migration '{}' failed and its FAILED marker could not be written: {}",
                        id, markerFailure.getMessage());
            }
            throw new SchemaMigrationException(
                    "Schema migration '" + id + "' failed after " + durationMs + "ms", e);
        }
        long durationMs = elapsedMs(startedNanos);
        // Deliberately not swallowed: an unwritten APPLIED marker means the
        // migration runs again on the next boot, and the operator has to know.
        writeMarker(id, SchemaMigrationState.APPLIED, durationMs, null);
        log.info("Schema migration '{}' applied in {}ms", id, durationMs);
    }

    private SchemaMigration instantiate(String id) {
        Class<? extends SchemaMigration> type = migrations.get(id);
        if (type == null) {
            throw new SchemaMigrationException("No schema migration registered for id '" + id + "'");
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SchemaMigrationException(
                    "Cannot instantiate schema migration '" + id + "' (" + type.getName() + ")", e);
        }
    }

    // ─── version bookkeeping ────────────────────────────────────────

    private Map<String, SchemaMigrationDocument> loadMarkers() {
        Map<String, SchemaMigrationDocument> byId = new LinkedHashMap<>();
        for (SchemaMigrationDocument doc : mongoTemplate.findAll(SchemaMigrationDocument.class)) {
            byId.put(doc.getId(), doc);
        }
        return byId;
    }

    /**
     * The database's version: the highest {@code APPLIED} id, empty string when
     * nothing has ever been applied. Markers this build does not know count too —
     * they describe what happened to the data, and ignoring them would re-run
     * migrations a newer build already applied.
     */
    private static String currentVersion(Map<String, SchemaMigrationDocument> markers) {
        return markers.values().stream()
                .filter(SchemaMigrationService::isDone)
                .map(SchemaMigrationDocument::getId)
                .max(String::compareTo)
                .orElse("");
    }

    /** Registered ids above {@code current}, in registry order. */
    private List<String> pendingAbove(String current) {
        return migrations.keySet().stream()
                .filter(id -> id.compareTo(current) > 0)
                .toList();
    }

    /**
     * APPLIED (ran) and BASELINED (deliberately not run on a new database) both
     * count as done and both raise the version. A FAILED marker is a breadcrumb,
     * not "done" — that migration is retried.
     */
    private static boolean isDone(@Nullable SchemaMigrationDocument marker) {
        return marker != null
                && (marker.getStatus() == SchemaMigrationState.APPLIED
                        || marker.getStatus() == SchemaMigrationState.BASELINED);
    }

    /**
     * Applied markers this build does not know. Usually the rollback case: a
     * newer build migrated the database and now older code boots against it.
     * Warn rather than fail — the data has already been transformed, refusing to
     * start would not undo it, and the linear version model keeps us from
     * re-running anything.
     */
    private void warnAboutUnknown(Map<String, SchemaMigrationDocument> markers) {
        List<String> unknown = markers.values().stream()
                .filter(SchemaMigrationService::isDone)
                .map(SchemaMigrationDocument::getId)
                .filter(id -> !migrations.containsKey(id))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            log.warn("Schema migrations: the database has {} applied migration(s) this build does "
                            + "not know: {}. Expected right after a rollback — the data was migrated "
                            + "by a newer build.",
                    unknown.size(), unknown);
        }
    }

    /**
     * Registered migrations at or below the current version without an
     * {@code APPLIED} marker. The linear model skips them — the right call for a
     * merge that inserted a migration with an older date, but it must not happen
     * quietly.
     */
    private void warnAboutSkipped(Map<String, SchemaMigrationDocument> markers, String current) {
        List<String> skipped = migrations.keySet().stream()
                .filter(id -> id.compareTo(current) <= 0)
                .filter(id -> !isDone(markers.get(id)))
                .toList();
        if (!skipped.isEmpty()) {
            log.warn("Schema migrations: {} registered migration(s) sit below the current database "
                            + "version '{}' and were never applied: {}. They will NOT run — the "
                            + "version model is linear. Re-register them with an id above '{}' if "
                            + "they are still needed.",
                    skipped.size(), current, skipped, current);
        }
    }

    private void writeMarker(
            String id, SchemaMigrationState state, long durationMs, @Nullable String error) {
        Class<? extends SchemaMigration> type = migrations.get(id);
        mongoTemplate.save(SchemaMigrationDocument.builder()
                .id(id)
                .status(state)
                .migrationClass(type == null ? null : type.getName())
                .appliedAt(Instant.now())
                .appliedByPod(ownerId)
                .durationMs(durationMs)
                .error(error)
                .build());
    }

    private SchemaMigrationReport report(
            List<String> applied, String version, boolean appliedByOtherPod) {
        return new SchemaMigrationReport(
                applied, migrations.size(), version, appliedByOtherPod, false);
    }

    // ─── helpers ────────────────────────────────────────────────────

    private static String resolveOwnerId() {
        String host = System.getenv("HOSTNAME");
        if (StringUtils.isBlank(host)) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                host = "unknown";
            }
        }
        return host + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName()
                + (StringUtils.isBlank(e.getMessage()) ? "" : ": " + e.getMessage());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SchemaMigrationException(
                    "Interrupted while waiting for the schema-migration lock", e);
        }
    }
}
