package de.mhus.vance.brain.sourceconfig;

/**
 * A factory that caches instances built from source-configuration documents
 * under one path prefix.
 *
 * <p>Exists so {@link SourceConfigDocumentListener} can invalidate without
 * knowing the three subsystems: each factory says which prefix it reads and
 * how to drop its cached entries. Adding a fourth kind of source needs no
 * change to the listener.
 */
public interface SourceConfigCache {

    /** The prefix from {@link SourceConfigPaths} this factory reads. */
    String configPathPrefix();

    /** Drop this project's cached instances; the next use rebuilds them. */
    void evict(String tenantId, String projectId);

    /**
     * Drop every project's cached instances in this tenant.
     *
     * <p>Needed because the configuration cascades and the cache does not: a
     * document in {@code _tenant} is part of what <em>every</em> project of the
     * tenant assembles, while the cache is keyed per project. Dropping only the
     * {@code _tenant} entry left every other project serving the old sources
     * until its TTL ran out — a source disabled tenant-wide stayed live for
     * five minutes in exactly the projects that were using it.
     */
    void evictTenant(String tenantId);
}
