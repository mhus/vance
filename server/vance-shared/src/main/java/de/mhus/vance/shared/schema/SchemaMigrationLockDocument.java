package de.mhus.vance.shared.schema;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Single-row lease that serialises migration runs across pods booting at the
 * same time ("new against new" in {@code planning/schema-migration.md} §3).
 * Always {@code _id = }{@value SchemaMigrationLockStore#LOCK_ID} — migrations
 * are per-database, so there is exactly one.
 *
 * <p>Not the same thing as the cluster-master lease: master election decides
 * who runs scheduled work and may itself read migrated data, so migrations must
 * not depend on it.
 */
@Document(collection = "schema_migration_lock")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaMigrationLockDocument {

    @Id
    private String id = "";

    /** Holder identity ({@code <host>/<uuid>}); {@code null} when free. */
    private @Nullable String owner;

    /** When the holder took it — informational. */
    private @Nullable Instant acquiredAt;

    /**
     * Expiry. A lease past this point is stealable so a crashed pod cannot
     * block every future boot. This is a safety valve, not a run budget: the
     * holder renews between migrations.
     */
    private @Nullable Instant expiresAt;
}
