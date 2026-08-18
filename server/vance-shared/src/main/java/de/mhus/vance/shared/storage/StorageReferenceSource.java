package de.mhus.vance.shared.storage;

import java.util.Collection;
import java.util.Set;

/**
 * Something that keeps blobs alive.
 *
 * <p>The orphan sweep deletes blobs nobody points at. Which "nobody"
 * means depends on what a deployment stores: in a brain that is documents
 * and their archives, in the kit store it is release artefacts. Before
 * this interface the sweeper knew those referrers by name, so anything
 * else that wrote a blob — an addon, a second service, a store reusing the
 * same chunked storage — had its data quietly eaten.
 *
 * <p><b>Silence must never mean "unreferenced".</b> That is the whole
 * risk of a pluggable deleter, and the guard rails live in
 * {@link StorageOrphanCleanupService}: no source registered means no
 * sweep, and a source that throws aborts the whole run rather than losing
 * its share of the blobs. A skipped sweep costs disk; a half-blind one
 * costs data.
 *
 * <p>Implementations answer for a <em>candidate set</em> rather than
 * enumerating everything they hold: the sweeper walks blobs in batches, so
 * a reverse lookup stays a bounded query and the JVM never holds more than
 * one batch.
 *
 * <p>Spec: {@code specification/kit-store.md} §10a.
 */
public interface StorageReferenceSource {

    /**
     * Which of {@code candidates} this source still points at.
     *
     * <p>Must be complete for the candidates given. Returning less than
     * the truth deletes live data — if a source cannot answer fully, it
     * must throw rather than under-report.
     */
    Set<String> findReferencedStorageIds(Collection<String> candidates);

    /**
     * What this source is, for the log line that says why blobs survived.
     *
     * <p>Default is the class name, which is enough to find the code; an
     * implementation with a nicer word may say so.
     */
    default String sourceName() {
        return getClass().getSimpleName();
    }
}
