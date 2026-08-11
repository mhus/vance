package de.mhus.vance.shared.schema;

/**
 * One data migration — a plain class, deliberately <b>not</b> a Spring bean.
 * A migration is needed once in the lifetime of an installation; as a bean it
 * would occupy a singleton on every boot forever after. It is referenced by
 * {@code Class} in {@link SchemaMigrationService}'s registry and instantiated
 * only when it is actually pending.
 *
 * <p>Consequence, and intended: <b>a migration cannot use Spring beans.</b>
 * Everything it gets is in {@link SchemaMigrationContext} — collection-level
 * access via {@code MongoTemplate}. That is also what makes the ordering
 * guarantee possible: migrations run before the repository layer exists, so no
 * service can read a shape (an enum value, a renamed field) that has not been
 * migrated yet.
 *
 * <p>Two rules for an implementor ({@code planning/schema-migration.md}):
 *
 * <ol>
 *   <li><b>Idempotent.</b> {@code up} must be safe to run twice. The marker
 *       document makes a second run unlikely, not impossible: a lease stolen
 *       from a crashed pod, or a retry after a partial failure, both replay it.
 *       Prefer self-emptying filters ({@code field exists: false},
 *       {@code type: OLD}) over "count rows, then rewrite all".</li>
 *   <li><b>Additive unless the deployment was choreographed.</b> A migration
 *       that changes the meaning of an existing field breaks any old pod still
 *       writing it (§1, the lost update). Quiescing old writers is the external
 *       deployment's job — this framework cannot detect it for you.</li>
 * </ol>
 *
 * <p>Implementations must be {@code public} with a {@code public} no-argument
 * constructor; both are checked when the registry is loaded, not when the
 * migration runs.
 */
public interface SchemaMigration {

    /**
     * Performs the migration. Throwing aborts the whole run and fails the boot
     * — the intended behaviour for a migration that cannot complete.
     */
    void up(SchemaMigrationContext context);
}
