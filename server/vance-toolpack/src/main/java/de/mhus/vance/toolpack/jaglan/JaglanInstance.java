package de.mhus.vance.toolpack.jaglan;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import de.mhus.vance.api.mount.MountedStat;

/**
 * A configured mount, ready to answer. Produced by
 * {@link JaglanProtocol#instantiate} and held in the project-scoped cache.
 *
 * <p>Only three methods are required — {@link #capabilities()},
 * {@link #stat} and {@link #list} plus {@link #open} — so a read-only source
 * is a small class. Everything a source may not be able to do
 * ({@link #write}, {@link #delete}, {@link #search}) has a default that
 * refuses, and refusing is a legitimate final answer, not a gap.
 *
 * <p>All paths are relative to the mount root, no leading slash, empty
 * string for the root itself. The {@code _ext/} namespace does not exist
 * here.
 */
public interface JaglanInstance {

    /** The mount name this instance serves. */
    String mount();

    /** The protocol that produced it. */
    String protocolId();

    /**
     * What this source allows and how long its answers may be cached.
     *
     * <p>Called behind a cache, not per request. An implementation that
     * fetches this remotely must tolerate being asked again after its TTL
     * and should keep the last answer on a failed refresh — a mount that
     * briefly reports nothing would otherwise read as "empty", and "empty"
     * and "gone" have to stay distinguishable.
     */
    JaglanCapabilities capabilities();

    /** Metadata for one entry, empty when the source does not have it. */
    Optional<MountedStat> stat(String pathInMount);

    /**
     * Direct children of a folder — one level, not recursive.
     *
     * @param pathInMount the folder, empty string for the mount root
     */
    List<MountedStat> list(String pathInMount);

    /** Open content for reading. The caller closes the stream. */
    InputStream open(String pathInMount);

    /**
     * Write content back. Default refuses.
     *
     * @throws JaglanProtocolException always, unless overridden
     */
    default MountedStat write(String pathInMount, InputStream content) {
        throw new JaglanProtocolException(mount(),
                "mount '" + mount() + "' is read-only (protocol " + protocolId() + ")");
    }

    /**
     * Delete at the source. Default refuses.
     *
     * @throws JaglanProtocolException always, unless overridden
     */
    default void delete(String pathInMount) {
        throw new JaglanProtocolException(mount(),
                "mount '" + mount() + "' is read-only (protocol " + protocolId() + ")");
    }

    /**
     * Search the source's own catalogue. Default returns nothing.
     *
     * <p>Empty is the honest answer for a source that cannot search, and the
     * caller checks {@link JaglanCapabilities#canSearch()} before asking —
     * so an empty list here never has to be read as "found nothing".
     */
    default List<MountedStat> search(String query, int limit) {
        return List.of();
    }

    /** Release protocol resources. Called when the project cache evicts. */
    default void dispose() {
        // nothing to release by default
    }
}
