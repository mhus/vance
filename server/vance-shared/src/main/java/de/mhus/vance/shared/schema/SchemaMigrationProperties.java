package de.mhus.vance.shared.schema;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.schema.*} — runtime configuration of the migration runner.
 * Defaults are the single-instance case: migrate on every boot, wait a little for
 * another pod that is already migrating.
 */
@Data
@ConfigurationProperties(prefix = "vance.schema")
public class SchemaMigrationProperties {

    /**
     * Run pending migrations while the context starts. Turning this off hands the
     * run to the external deploy choreography
     * ({@code planning/schema-migration.md} §4) — the process then boots against a
     * possibly un-migrated database, so only do it when something else guarantees
     * the run. Anus sets it to {@code false}: it shares the engine but must not
     * migrate on every CLI invocation.
     */
    private boolean migrateOnBoot = true;

    /**
     * Lease duration of the migration lock. A safety valve for a pod that dies
     * mid-run, not a budget for the run itself: the holder renews before every
     * further migration. A <em>single</em> migration that outlives the lease can
     * have the lock stolen — which is why {@link SchemaMigration} demands
     * idempotency.
     */
    private Duration lockTtl = Duration.ofMinutes(15);

    /**
     * How long to wait for another pod that holds the lock. On timeout the boot
     * fails: the database is not verifiably at the version this build needs, and
     * starting anyway is how data gets corrupted.
     */
    private Duration lockWait = Duration.ofMinutes(5);

    /** Poll interval while waiting for the lock. */
    private Duration lockPollInterval = Duration.ofSeconds(2);
}
