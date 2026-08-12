package de.mhus.vance.shared.schema;

/**
 * Outcome recorded in a {@link SchemaMigrationDocument}.
 *
 * <p>{@link #APPLIED} and {@link #BASELINED} both count as "done" and both raise
 * the database version; they differ in whether the migration actually ran.
 * {@link #FAILED} is a breadcrumb for the operator, not a state that stops a
 * retry: the next boot sees the migration as pending again and runs it.
 */
public enum SchemaMigrationState {

    /** The migration ran and completed. */
    APPLIED,

    /**
     * The migration was <em>not</em> run: the database carried no marker at all
     * when this build first looked at it, so it was taken to be a new database
     * that is already at the current shape ({@link SchemaMigrationService}
     * baseline). Kept distinct from {@link #APPLIED} so the history does not
     * claim work that never happened.
     */
    BASELINED,

    /** The migration ran and threw. Retried on the next boot. */
    FAILED
}
