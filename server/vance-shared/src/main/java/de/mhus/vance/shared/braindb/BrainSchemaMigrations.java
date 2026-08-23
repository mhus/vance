package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.BaselineAnchorMigration;
import de.mhus.vance.shared.schema.SchemaMigrationSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Every migration the brain's database needs, in ascending id order.
 *
 * <p>This is the list that used to sit in {@code SchemaMigrationService}
 * itself. It moved out when the kit store started using the same machinery:
 * a static field would have been inherited by every application that
 * scanned the package, and these migrations only make sense against the
 * brain's collections.
 *
 * <p><b>Why this package.</b> Not under {@code schema}: the kit store
 * scans that package to reuse the machinery, and a source sitting in a
 * subpackage of it would be scanned along — the store would have loaded
 * the brain's registry and stamped the brain's ids into the store's
 * database. The machinery is shared, the list must not be.
 *
 * <p><b>Why here and not in vance-brain.</b> A source belongs to a
 * <em>database</em>, not to a process. Brain, anus and every brain addon
 * run against the same one and must agree on its version — anus reads this
 * very list with {@code vance.schema.migrate-on-boot=false}, and it does
 * not depend on vance-brain, so a list living there would leave anus with
 * no source at all. The collections these migrations reshape
 * ({@code DocumentDocument}, {@code SettingDocument}, …) are declared in
 * this module too, which is the same statement from the other side.
 *
 * <p>An addon with collections of its own is free to contribute a second
 * source; the service merges them and only requires ids to be unique.
 *
 * <p>The first entry is the anchor {@link BaselineAnchorMigration}: it does
 * nothing and exists so an existing database is "known" before the first real
 * migration ever ships — see its class comment. The three hand-written
 * {@code @PostConstruct} backfills in vance-brain still run the old way
 * ({@code planning/schema-migration.md} §3); moving them over is a separate
 * track.
 *
 * <p>Integrity — unique, ascending, instantiable — is asserted by
 * {@code SchemaMigrationRegistryTest}, not re-checked on every boot.
 */
@Component
public class BrainSchemaMigrations implements SchemaMigrationSource {

    private static final List<Registered> MIGRATIONS = List.of(
            new Registered("2026-08-12_001", BaselineAnchorMigration.class),
            new Registered("2026-08-21_001", Migrator_2026_08_21_001_ProjectLease.class),
            // runOnBaseline: these two decide whether a project is kept on a
            // pod, and they are the only writers of that decision for existing
            // data. A database restored from before the anchor release looks
            // new from here and would be baselined — leaving every project on
            // lifecycleType=EPHEMERAL, which the new code reads as "never start
            // this by itself". Schedulers, hooks and events would then never be
            // placed again, silently, and no later boot or product action puts
            // it right. Both are self-emptying filters, so on a genuinely new
            // database they cost one query each and change nothing. They travel
            // together: _001 sets the flag including kit provisioning and _002
            // takes provisioning back out.
            new Registered("2026-08-22_001", Migrator_2026_08_22_001_OwnerRequired.class,
                    /*runOnBaseline*/ true),
            new Registered("2026-08-22_002",
                    Migrator_2026_08_22_002_OwnerRequiredWithoutProvisioning.class,
                    /*runOnBaseline*/ true),
            // Not runOnBaseline: a genuinely new database has no event_log to
            // drop, and being stamped without running is exactly right.
            new Registered("2026-08-23_001", Migrator_2026_08_23_001_DropEventLog.class),
            // runOnBaseline: this is the only writer of the inbox collection's
            // new location, and the code only reads the new name. A database
            // restored from before the anchor looks new here and would be
            // baselined — the rows would stay in inbox_items while every query
            // goes to maximegalon_threads, so every inbox appears *empty*
            // rather than broken, and nothing later puts it right. On a new
            // database it costs one collectionExists call.
            new Registered("2026-08-23_002", Migrator_2026_08_23_002_MaximegalonRename.class,
                    /*runOnBaseline*/ true),
            // Not runOnBaseline: a new database has no threads to backfill, and
            // unlike the rename above a skipped run is recoverable — the fields
            // are additive and a missing unreadFor reads as "nothing unread",
            // not as a lost collection.
            new Registered("2026-08-23_003",
                    Migrator_2026_08_23_003_MaximegalonThreadFields.class));

    @Override
    public List<Registered> migrations() {
        return MIGRATIONS;
    }

    @Override
    public String sourceName() {
        return "brain";
    }
}
