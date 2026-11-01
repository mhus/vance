package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import org.bson.Document;

/**
 * Folds the two transient project statuses into the desired state.
 *
 * <p>{@code ProjectDocument.status} used to mix intent and observation:
 * {@code RECOVERING} and {@code SUSPENDING} described what a pod was doing, not
 * what anybody wanted. Observation now lives in {@code actualState}, so the two
 * values no longer exist in the {@code ProjectStatus} enum and a row still
 * carrying one would fail to load.
 *
 * <p>The mapping follows the intent each value stood for: a project that was
 * {@code RECOVERING} was on its way to {@code RUNNING}, one that was
 * {@code SUSPENDING} on its way to {@code SUSPENDED}. {@code actualState} needs
 * no backfill — an absent field reads as {@code STOPPED}, which is what a
 * project without a live lease is.
 *
 * <p>Idempotent: re-running finds nothing left to rewrite.
 */
public final class Migrator_2026_11_01_001_ProjectDesiredState implements SchemaMigration {

    private static final String COLLECTION = "projects";

    @Override
    public void up(SchemaMigrationContext context) {
        var projects = context.mongoTemplate().getCollection(COLLECTION);
        long recovering = projects.updateMany(
                        new Document("status", "RECOVERING"), new Document("$set", new Document("status", "RUNNING")))
                .getModifiedCount();
        long suspending = projects.updateMany(
                        new Document("status", "SUSPENDING"), new Document("$set", new Document("status", "SUSPENDED")))
                .getModifiedCount();
        System.getLogger(Migrator_2026_11_01_001_ProjectDesiredState.class.getName())
                .log(
                        System.Logger.Level.INFO,
                        "project status: RECOVERING→RUNNING on " + recovering + ", SUSPENDING→SUSPENDED on "
                                + suspending + " row(s)");
    }
}
