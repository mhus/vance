package de.mhus.vance.shared.schema;

/**
 * Outcome recorded in a {@link SchemaMigrationDocument}.
 *
 * <p>Only {@link #APPLIED} means "done". A {@link #FAILED} marker is a
 * breadcrumb for the operator, not a state that stops a retry: the next boot
 * sees the migration as pending again and runs it.
 */
public enum SchemaMigrationState {
    APPLIED,
    FAILED
}
