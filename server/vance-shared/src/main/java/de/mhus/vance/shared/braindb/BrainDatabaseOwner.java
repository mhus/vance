package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.database.DatabaseOwner;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * This is the brain's database.
 *
 * <p>Brain, anus and the brain addons all declare the same owner because
 * they all run against the same database — the identity names the
 * database, not the process. They find this bean by scanning
 * {@code de.mhus.vance.shared} whole; the kit store scans only the
 * machinery packages and therefore never sees it.
 */
@Component
public class BrainDatabaseOwner implements DatabaseOwner {

    /** Written into live databases. Never rename. */
    public static final String OWNER = "brain";

    @Override
    public String owner() {
        return OWNER;
    }

    /**
     * Collections only the kit store creates. Their {@code store_} prefix
     * is not decoration — it is what makes this list short and safe.
     */
    @Override
    public Set<String> foreignCollections() {
        return Set.of("store_users", "store_kits", "store_kit_releases");
    }
}
