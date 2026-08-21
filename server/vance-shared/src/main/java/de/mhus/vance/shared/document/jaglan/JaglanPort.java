package de.mhus.vance.shared.document.jaglan;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import de.mhus.vance.api.documents.MountSearchOutcome;
import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.api.mount.MountedStat;

/**
 * What {@code DocumentService} calls instead of {@code StorageService} when a
 * path lives under {@code _ext/} — the seam between the document layer and
 * Jaglan.
 *
 * <p>It is an interface in {@code vance-shared} rather than a service in
 * {@code vance-brain} because {@code DocumentService} lives here and shared
 * must not depend on brain. The dispatcher, the protocols and the
 * per-project source factory sit in {@code de.mhus.vance.brain.jaglan}, and
 * the brain-side implementation of this port bridges the two.
 *
 * <p><b>Optional by design.</b> Resolved through {@code ObjectProvider}, the
 * way {@code DocumentService} already resolves {@code PermissionService}. No
 * implementation present is the normal state, not an edge case — anus loads
 * {@code vance-shared} too, and any process without the Jaglan addon has no
 * port. Callers must then refuse {@code _ext} paths with a named error, not
 * fail with an NPE.
 *
 * <p>Paths in this contract are <b>mount-relative</b>, never
 * {@code _ext/<mount>/...}. Splitting a document path into
 * {@code (mount, pathInMount)} is {@link JaglanPaths}' job, so no protocol
 * implementation ever learns about the namespace.
 */
public interface JaglanPort {

    /**
     * The mounts configured for this project — the question behind "should
     * {@code _ext} appear in this folder listing at all".
     *
     * <p><b>Cache-only.</b> This sits on the hot path of three listing
     * surfaces ({@code extractFolders}, {@code listFolders},
     * {@code listByFolder}), so it resolves configuration and reads whatever
     * the capabilities cache holds. It must never touch a source: a project
     * with five configured mounts, three of them dead, would otherwise pay
     * three timeouts before the folder tree renders. A cold cache reports
     * {@code UNKNOWN}/{@code null} and fills in on a later listing.
     *
     * <p>An unreachable mount is still <b>reported</b> (with
     * {@code access = UNKNOWN}); only an unconfigured one is absent.
     *
     * @return the mounts, empty when this project has none
     */
    List<MountedSource> mounts(String tenantId, String projectId);

    /**
     * Metadata for one entry, or empty when the mount does not have it.
     *
     * <p>The lazy-stat behind {@code findByPath}: one remote call on a cache
     * miss, after which the metadata shell row exists and answers from Mongo
     * until its TTL runs out.
     */
    Optional<MountedStat> stat(String tenantId, String projectId, String mount, String pathInMount);

    /**
     * Direct children of a folder inside a mount — files and directories,
     * one level, not recursive.
     *
     * @param pathInMount the folder, empty string for the mount root
     */
    List<MountedStat> list(String tenantId, String projectId, String mount, String pathInMount);

    /**
     * Ask a mount to search its own catalogue.
     *
     * <p>Delegated on purpose. Brain-side RAG does not apply to mounted
     * content — indexing a foreign library into our own vector store is not
     * something we want — but the library itself usually can search, and
     * asking it beats walking its tree.
     *
     * <p>A mount that declares it cannot search is <b>not asked</b>, and says
     * so through {@link MountSearchResult#outcome()} rather than through an
     * empty list — an empty list is what "found nothing" looks like, and the
     * two must not be the same answer.
     *
     * @param limit already clamped by the caller
     */
    default MountSearchResult search(
            String tenantId, String projectId, String mount, String query, int limit) {
        return new MountSearchResult(List.of(), MountSearchOutcome.UNSUPPORTED);
    }

    /**
     * Hits plus why — the outcome cannot be inferred from an empty list.
     *
     * <p>It is decided <b>here</b>, in the dispatcher, because that is where
     * the capabilities live. Unlike the folder listing, which reads them
     * cache-only to stay off the network, a search is one explicit action
     * against one named mount: the same trade-off {@code stat} and
     * {@code list} already make, so the declaration is fetched if it is not
     * cached. Deciding it from a cold cache instead produced the worst
     * possible answer — "this source cannot search" about a source that can.
     */
    record MountSearchResult(List<MountedStat> hits, MountSearchOutcome outcome) {}

    /**
     * Open the content for reading. The caller closes the stream.
     *
     * <p>Streamed through, never copied into {@code StorageService} — that
     * is the whole point of the mount. Callers must not buffer the whole
     * thing to answer a size question; {@link MountedStat#size()} exists.
     */
    InputStream open(String tenantId, String projectId, String mount, String pathInMount);

    /**
     * Write content back to the source and return the resulting metadata.
     *
     * @throws JaglanAccessException when the source is read-only. Refusing is
     *         the source's right, and it is reported as a named failure
     *         rather than a silent no-op — a save that appears to succeed
     *         and is gone after a reload is worse than a rejection.
     */
    MountedStat write(String tenantId, String projectId, String mount, String pathInMount,
            InputStream content);

    /**
     * Delete at the source.
     *
     * <p>There is no trash for mounted documents: the trash folder lives
     * outside {@code _ext/}, so moving a document there would break the
     * mount address and the derived id. Deleting means deleting at the
     * source, or not at all.
     *
     * @throws JaglanAccessException when the source is read-only
     */
    void delete(String tenantId, String projectId, String mount, String pathInMount);
}
