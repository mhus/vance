package de.mhus.vance.shared.schema;

/**
 * Thrown when the database cannot be brought to the version this build needs.
 * Escaping from the boot runner it fails the Spring context — that is the
 * intent: the migrator is also the compatibility gate
 * ({@code planning/schema-migration.md} §3).
 */
public class SchemaMigrationException extends RuntimeException {

    public SchemaMigrationException(String message) {
        super(message);
    }

    public SchemaMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
