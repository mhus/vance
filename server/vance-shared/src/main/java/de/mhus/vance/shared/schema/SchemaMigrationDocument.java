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
 * Marker and history entry for one migration — {@code _id} is the registry id
 * (an ISO date), so the unique index on {@code _id} is what makes "applied" a
 * fact rather than a belief.
 *
 * <p>The <em>version</em> of the database is derived from these rows (the highest
 * applied id); the rows themselves stay per-migration so an operator can see when
 * each ran, how long it took, and what failed.
 */
@Document(collection = "schema_migrations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaMigrationDocument {

    /** Registry id, e.g. {@code "2026-08-01"}. */
    @Id
    private String id = "";

    /** {@link SchemaMigrationState#APPLIED} is the only state that counts as done. */
    private SchemaMigrationState status = SchemaMigrationState.APPLIED;

    /** Implementing class at the time of the run — for reading the collection by hand. */
    private @Nullable String migrationClass;

    /** When the run finished, successfully or not. */
    private @Nullable Instant appliedAt;

    /** Identity of the pod that ran it — {@code <host>/<uuid8>}, informational. */
    private @Nullable String appliedByPod;

    /** Wall-clock duration of the {@code up()} call. */
    private long durationMs;

    /** Exception message of a failed run; {@code null} when applied. */
    private @Nullable String error;
}
