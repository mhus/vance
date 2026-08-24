package de.mhus.vance.shared.document.jaglan;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.documents.MountSearchOutcome;
import de.mhus.vance.api.documents.WriterRole;
import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentStatus;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Keeps the metadata shell rows for mounted documents in step with their
 * sources: stat on demand, cache for the TTL the source declared, list a
 * folder when someone opens it, and remember an outage briefly so a dead
 * mount does not cost a timeout per call.
 *
 * <p><b>Collaborator of {@code DocumentService}, not a public service.</b> It
 * writes {@code DocumentDocument} rows directly, which is normally
 * {@code DocumentService}'s exclusive territory; the same arrangement already
 * exists for {@code DocumentArchiveService}, which pointer-moves a live
 * document's {@code storageId}. Nothing outside this package should call it —
 * go through {@code DocumentService.findByPath} / {@code listMountedFolder}.
 *
 * <p>What a shell row is and is not: it carries path, name, mime type, size
 * and an expiry, and it never carries a {@code storageId} — the absence of
 * that handle is exactly what marks the content as living elsewhere. See
 * {@code planning/jaglan-mounted-docs.md} §3 and §6.
 */
@Service
@Slf4j
public class JaglanShellService {

    private final MongoTemplate mongoTemplate;
    private final ObjectProvider<JaglanPort> portProvider;

    /**
     * How long a mount stays "known bad" after a failed call.
     *
     * <p>Per-pod RAM and deliberately not Mongo: it is transient state about
     * reachability, not about content, and the cost of two pods each learning
     * it separately is one extra timeout. Without it, a dead mount pays its
     * timeout on every single listing — which is how a broken mount turns
     * into a slow application rather than a visibly broken one.
     */
    @Value("${vance.jaglan.outage-memory-seconds:30}")
    private long outageMemorySeconds = 30;

    /**
     * The most entries a single mount folder may have before this refuses to
     * materialise it.
     *
     * <p><b>Why a refusal and not a page.</b> A listing is authoritative for
     * its folder — {@link #pruneVanished} deletes every row the listing did
     * not mention. Taking the first N entries of a wide folder would therefore
     * not be a partial view, it would be a deletion of everything after N.
     * The contract's completeness requirement is load-bearing, so the only
     * honest reaction to a folder that cannot be listed completely is to say
     * so and write nothing.
     *
     * <p><b>Why a limit at all.</b> Without one, a mount pointed at a
     * directory of 100k files turns one expand into 100k upserts plus a
     * folder-wide prune scan — the source is within its rights, the reader is
     * not prepared for it. A source that expects to hold that many entries
     * partitions them (the archive source's tree is year/month/day/hour/minute
     * for exactly this reason).
     *
     * <p>{@code 0} disables the limit.
     */
    @Value("${vance.jaglan.max-folder-entries:5000}")
    private int maxFolderEntries = 5000;

    /** Key is {@code tenant|project|mount}; value is when to try again. */
    private final Map<String, Instant> outages = new ConcurrentHashMap<>();

    /** What a read-only source's row carries — every writer refused. */
    private static final Set<WriterRole> MOUNT_LOCK =
            Set.of(WriterRole.AI, WriterRole.USER, WriterRole.KIT);

    public JaglanShellService(
            MongoTemplate mongoTemplate, ObjectProvider<JaglanPort> portProvider) {
        this.mongoTemplate = mongoTemplate;
        this.portProvider = portProvider;
    }

    // ─── mounts ─────────────────────────────────────────────────────────

    /**
     * The mounts configured for this project, or empty when there is no
     * Jaglan implementation in this process.
     *
     * <p>Cheap enough for a folder listing to call: the port answers from
     * configuration plus its capabilities cache and never touches a source.
     */
    public List<MountedSource> mounts(String tenantId, String projectId) {
        JaglanPort port = portProvider.getIfAvailable();
        if (port == null) return List.of();
        try {
            return port.mounts(tenantId, projectId);
        } catch (RuntimeException e) {
            // A listing must not fail because the mount configuration is
            // broken — the folder tree is not the place to surface that.
            log.warn("Failed to resolve mounts for {}/{}: {}", tenantId, projectId, e.toString());
            return List.of();
        }
    }

    /**
     * Drop the resolved mounts and their declarations for this project.
     *
     * <p>Only the caches — the shell rows stay, because they are content
     * knowledge and a configuration re-read says nothing about them. Dropping
     * them here would turn a "did I type the setting right" click into a
     * re-stat of everything the project had ever browsed.
     */
    public void refreshMounts(String tenantId, String projectId) {
        JaglanPort port = portProvider.getIfAvailable();
        if (port == null) return;
        try {
            port.refresh(tenantId, projectId);
        } catch (RuntimeException e) {
            log.warn("Failed to refresh mounts for {}/{}: {}", tenantId, projectId, e.toString());
        }
    }

    /** The configured mount by name, or empty when unknown. */
    public Optional<MountedSource> findMount(String tenantId, String projectId, String mount) {
        return mounts(tenantId, projectId).stream()
                .filter(m -> m.name().equals(mount))
                .findFirst();
    }

    // ─── single entry ───────────────────────────────────────────────────

    /**
     * Resolve one mounted path to a shell row, stat'ing the source when the
     * cached row is missing or stale.
     *
     * <p>Failure keeps the last answer: if the source cannot be reached, an
     * existing row is returned as-is rather than deleted. A reader that gets
     * "not found" concludes the file does not exist, which is a worse lie
     * than slightly stale metadata.
     *
     * @return the row, or empty when the source says the path is not there
     *         (and when there is nothing cached to fall back on)
     */
    public Optional<DocumentDocument> resolve(String tenantId, String projectId, String path) {
        String mount = JaglanPaths.mountNameOf(path);
        String inMount = JaglanPaths.pathInMount(path);
        String id = JaglanPaths.documentId(tenantId, projectId, mount, inMount);
        Instant now = Instant.now();

        DocumentDocument cached = mongoTemplate.findById(id, DocumentDocument.class);
        if (cached != null && isUnconfigured(tenantId, projectId, mount)) {
            // The mount was removed from the configuration. Its rows are the
            // second way a shell row is allowed to disappear (see §2.2): left
            // alone they stay a browsable tree of readable metadata whose
            // content can never be fetched again, and nothing else would ever
            // clear them.
            log.info("Jaglan: mount '{}' is no longer configured in {}/{} — dropping its rows",
                    mount, tenantId, projectId);
            evictMount(tenantId, projectId, mount);
            return Optional.empty();
        }
        if (cached != null && isFresh(cached, now)) {
            return Optional.of(decorate(cached, tenantId, projectId, mount));
        }
        if (isInOutage(tenantId, projectId, mount, now)) {
            // Known bad — do not pay the timeout again. Stale beats absent.
            return Optional.ofNullable(cached).map(d -> decorate(d, tenantId, projectId, mount));
        }

        JaglanPort port = portProvider.getIfAvailable();
        if (port == null) {
            return Optional.ofNullable(cached).map(d -> decorate(d, tenantId, projectId, mount));
        }

        Optional<MountedStat> stat;
        try {
            stat = port.stat(tenantId, projectId, mount, inMount);
        } catch (RuntimeException e) {
            rememberOutage(tenantId, projectId, mount, now, e);
            return Optional.ofNullable(cached).map(d -> decorate(d, tenantId, projectId, mount));
        }

        if (stat.isEmpty()) {
            // The source answered and said no. That is authoritative — drop
            // the stale row so the path stops resolving.
            if (cached != null) {
                mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)),
                        DocumentDocument.class);
            }
            return Optional.empty();
        }
        Duration ttl = ttlFor(tenantId, projectId, mount);
        DocumentDocument row = upsertShell(tenantId, projectId, mount, stat.get(), ttl,
                accessOf(tenantId, projectId, mount), now);
        return Optional.of(decorate(row, tenantId, projectId, mount));
    }

    // ─── folder ─────────────────────────────────────────────────────────

    /**
     * Bring a mount folder's shell rows up to date and return them.
     *
     * @param force ignore the folder marker's TTL — the explicit
     *              "refresh this folder" gesture. Per-folder rather than
     *              global because a mount can be large.
     */
    public List<DocumentDocument> listFolder(
            String tenantId, String projectId, String mount, String folderInMount, boolean force) {
        Instant now = Instant.now();
        String stateId = JaglanPaths.folderStateId(tenantId, projectId, mount, folderInMount);
        JaglanFolderState state = mongoTemplate.findById(stateId, JaglanFolderState.class);

        if (state != null && isUnconfigured(tenantId, projectId, mount)) {
            // Same reasoning as in resolve(): an unconfigured mount leaves a
            // ghost tree behind, and a listing is where it is noticed.
            log.info("Jaglan: mount '{}' is no longer configured in {}/{} — dropping its rows",
                    mount, tenantId, projectId);
            evictMount(tenantId, projectId, mount);
            return List.of();
        }

        boolean usable = !force && state != null && state.isFresh(now);
        if (!usable && !isInOutage(tenantId, projectId, mount, now)) {
            JaglanPort port = portProvider.getIfAvailable();
            if (port != null) {
                try {
                    List<MountedStat> entries =
                            port.list(tenantId, projectId, mount, folderInMount);
                    Duration ttl = ttlFor(tenantId, projectId, mount);
                    if (isOversized(entries.size())) {
                        // Nothing written and nothing pruned: this is not a
                        // partial view of the folder, it is a refusal to hold
                        // it. Recorded as a folder failure so the surfaces say
                        // "too large" instead of showing an empty folder —
                        // which is what "we did not list it" would look like.
                        String message = "folder holds " + entries.size()
                                + " entries, above the limit of " + maxFolderEntries
                                + " — partition the source or mount a narrower path";
                        log.warn("Jaglan: refusing to materialise '{}' in mount '{}' ({}/{}): {}",
                                folderInMount, mount, tenantId, projectId, message);
                        writeFolderState(stateId, tenantId, projectId, mount, folderInMount,
                                state == null ? null : state.getListedAt(),
                                state == null ? 0 : state.getEntryCount(),
                                Duration.ofSeconds(outageMemorySeconds), message);
                        return readFolderRows(tenantId, projectId, mount, folderInMount);
                    }
                    MountAccess mountAccess = accessOf(tenantId, projectId, mount);
                    for (MountedStat entry : entries) {
                        upsertShell(tenantId, projectId, mount, entry, ttl, mountAccess, now);
                    }
                    pruneVanished(tenantId, projectId, mount, folderInMount, entries);
                    writeFolderState(stateId, tenantId, projectId, mount, folderInMount,
                            now, entries.size(), ttl, null);
                } catch (RuntimeException e) {
                    rememberOutage(tenantId, projectId, mount, now, e);
                    // Keep whatever rows we already have and record the
                    // failure without clearing listedAt — "we saw this folder
                    // once" survives an outage.
                    writeFolderState(stateId, tenantId, projectId, mount, folderInMount,
                            state == null ? null : state.getListedAt(),
                            state == null ? 0 : state.getEntryCount(),
                            Duration.ofSeconds(outageMemorySeconds), e.toString());
                }
            }
        }
        return readFolderRows(tenantId, projectId, mount, folderInMount);
    }

    /**
     * One synthetic folder entry per configured mount, for the folder-listing
     * surfaces — the answer to "what sits under {@code _ext/}".
     *
     * <p>Cache-only, like {@link #mounts}: no source is touched. The counts
     * follow a precedence that never guesses (see {@code FolderInfo}):
     *
     * <ol>
     *   <li>the mount root was listed and the marker is still fresh → the
     *       shell rows <i>are</i> its contents, so count them;</li>
     *   <li>otherwise the source's own declaration, if it made one;</li>
     *   <li>otherwise {@code null} — unknown.</li>
     * </ol>
     */
    public List<MountFolderView> mountFolders(String tenantId, String projectId) {
        List<MountedSource> sources = mounts(tenantId, projectId);
        if (sources.isEmpty()) return List.of();
        Instant now = Instant.now();
        List<MountFolderView> out = new ArrayList<>(sources.size());
        for (MountedSource source : sources) {
            Integer documents = null;
            Integer subfolders = null;
            if (isRootListingFresh(tenantId, projectId, source.name(), now)) {
                documents = countKnownRootFiles(tenantId, projectId, source.name());
                subfolders = countKnownRootSubfolders(tenantId, projectId, source.name());
            } else if (source.itemCount() != null) {
                documents = (int) Math.min(source.itemCount(), Integer.MAX_VALUE);
            }
            out.add(new MountFolderView(source.name(), documents, subfolders));
        }
        return List.copyOf(out);
    }

    /** A mount as a folder under {@code _ext/}, with counts that may be
     *  unknown. {@code null} means unknown, never zero. */
    public record MountFolderView(
            String mount, @Nullable Integer documentCount, @Nullable Integer subfolderCount) {}

    private boolean isRootListingFresh(
            String tenantId, String projectId, String mount, Instant now) {
        JaglanFolderState state = mongoTemplate.findById(
                JaglanPaths.folderStateId(tenantId, projectId, mount, ""),
                JaglanFolderState.class);
        return state != null && state.isFresh(now);
    }

    /**
     * Files directly inside the mount root.
     *
     * <p>Direct children only, and directory rows excluded — the number sits
     * next to {@code subfolderCount}, so the two have to describe the same
     * object. Counting every row under the prefix mixed in the folder rows
     * (already counted as subfolders) and everything a deeper browse had
     * pulled in, which made "5 documents" appear next to "2 folders" for a
     * root holding three files.
     */
    private int countKnownRootFiles(String tenantId, String projectId, String mount) {
        String prefix = JaglanPaths.mountRootPath(mount) + "/";
        String childRegex = "^" + java.util.regex.Pattern.quote(prefix) + "[^/]+$";
        return (int) mongoTemplate.count(
                Query.query(Criteria.where("tenantId").is(tenantId)
                        .and("projectId").is(projectId)
                        .and("path").regex(childRegex)
                        .and("mountDirectory").ne(true)),
                DocumentDocument.class);
    }

    private int countKnownRootSubfolders(String tenantId, String projectId, String mount) {
        int subfolders = 0;
        for (DocumentDocument row : readFolderRows(tenantId, projectId, mount, "")) {
            if (row.isMountDirectory()) subfolders++;
        }
        return subfolders;
    }

    /**
     * Names of the mount folders directly inside {@code folderPath} — the
     * folder rows a listing must show as folders rather than as empty files.
     *
     * @param folderPath a full {@code _ext/<mount>/…} path
     */
    public List<String> directoryNamesIn(String tenantId, String projectId, String folderPath) {
        if (!JaglanPaths.isMounted(folderPath)) return List.of();
        List<String> out = new ArrayList<>();
        try {
            String mount = JaglanPaths.mountNameOf(folderPath);
            for (DocumentDocument row
                    : readFolderRows(tenantId, projectId, mount,
                            JaglanPaths.pathInMount(folderPath))) {
                if (!row.isMountDirectory()) continue;
                String path = row.getPath();
                int slash = path.lastIndexOf('/');
                out.add(slash < 0 ? path : path.substring(slash + 1));
            }
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        return out;
    }

    /**
     * Ask the mounts of a project to search their own catalogues, and upsert
     * shell rows for what comes back.
     *
     * <p>The rows are written rather than only returned so a hit is
     * immediately readable: without them the first {@code doc_read} on a result
     * would pay another stat against the source for a file it just described.
     *
     * @param mount only this mount, or {@code null} for every configured one
     *              whose declaration says it can search
     */
    public List<DocumentDocument> search(
            String tenantId, String projectId, @Nullable String mount, String query, int limit) {
        List<DocumentDocument> out = new ArrayList<>();
        for (MountedSource source : mounts(tenantId, projectId)) {
            if (mount != null && !mount.equals(source.name())) continue;
            if (out.size() >= limit) break;
            out.addAll(searchInMount(
                    tenantId, projectId, source.name(), query, limit - out.size()).hits());
        }
        return out;
    }

    /**
     * Ask one mount, and say what came of it.
     *
     * <p>The outcome is the point: a caller that only gets a list cannot tell
     * an empty answer from an unasked question, and inside a mount those are
     * the two common cases. See {@code MountSearchOutcome}.
     */
    public MountSearch searchInMount(
            String tenantId, String projectId, String mount, String query, int limit) {
        JaglanPort port = portProvider.getIfAvailable();
        if (port == null) {
            return new MountSearch(List.of(), MountSearchOutcome.UNAVAILABLE);
        }
        if (query == null || query.isBlank()) {
            return new MountSearch(List.of(), MountSearchOutcome.UNSUPPORTED);
        }
        Instant now = Instant.now();
        if (isInOutage(tenantId, projectId, mount, now)) {
            return new MountSearch(List.of(), MountSearchOutcome.UNAVAILABLE);
        }
        // Whether the mount can search is the dispatcher's answer, not ours:
        // it holds the capabilities and may fetch them. Reading the cache-only
        // mount list here reported "cannot search" for a cold cache.
        JaglanPort.MountSearchResult result;
        try {
            result = port.search(tenantId, projectId, mount, query, limit);
        } catch (RuntimeException e) {
            // One failing mount must not fail a multi-mount search, and must
            // not look like an answer in a single-mount one.
            rememberOutage(tenantId, projectId, mount, now, e);
            return new MountSearch(List.of(), MountSearchOutcome.UNAVAILABLE);
        }
        if (result.outcome() != MountSearchOutcome.DELEGATED) {
            return new MountSearch(List.of(), result.outcome());
        }
        Duration ttl = ttlFor(tenantId, projectId, mount);
        MountAccess mountAccess = accessOf(tenantId, projectId, mount);
        List<DocumentDocument> rows = new ArrayList<>(result.hits().size());
        for (MountedStat hit : result.hits()) {
            if (rows.size() >= limit) break;
            rows.add(decorate(
                    upsertShell(tenantId, projectId, mount, hit, ttl, mountAccess, now),
                    tenantId, projectId, mount));
        }
        return new MountSearch(rows, MountSearchOutcome.DELEGATED);
    }

    /** Hits plus what actually happened. */
    public record MountSearch(List<DocumentDocument> hits, MountSearchOutcome outcome) {}

    /**
     * Why the last attempt to refresh this mount folder failed, or
     * {@code null} when the last one succeeded.
     *
     * <p>The rows of a folder whose refresh failed are still returned — that
     * is the point of keeping them — but they are <b>older than they look</b>,
     * and nothing else says so. The per-mount status line in
     * {@code MountedSource} answers a different question: it comes from the
     * capabilities cache's failure memory, so it is silent about a source that
     * describes itself happily and cannot list this one folder.
     *
     * @param folderInMount mount-relative folder, empty string for the root
     */
    public @Nullable FolderFailure folderFailure(
            String tenantId, String projectId, String mount, String folderInMount) {
        JaglanFolderState state = mongoTemplate.findById(
                JaglanPaths.folderStateId(tenantId, projectId, mount, folderInMount),
                JaglanFolderState.class);
        if (state == null || state.getFailureMessage() == null || state.getFailedAt() == null) {
            return null;
        }
        return new FolderFailure(state.getFailedAt(), state.getFailureMessage());
    }

    /** When a folder listing last failed, and what it said. */
    public record FolderFailure(Instant at, String message) {}

    /**
     * Drop every shell row and folder marker of a mount.
     *
     * <p>Called when the mount is gone from the configuration — the second of
     * the two ways a shell row may disappear (the first is a listing pruning
     * an entry the source no longer reports). Deliberately <b>not</b> part of
     * {@link #refreshMounts}: re-reading the configuration says nothing about
     * content, and dropping everything on a "did I type the setting right"
     * click would re-stat whatever the project had ever browsed.
     */
    public void evictMount(String tenantId, String projectId, String mount) {
        String prefix = JaglanPaths.mountRootPath(mount) + "/";
        mongoTemplate.remove(Query.query(Criteria.where("tenantId").is(tenantId)
                        .and("projectId").is(projectId)
                        .and("path").regex("^" + java.util.regex.Pattern.quote(prefix))),
                DocumentDocument.class);
        mongoTemplate.remove(Query.query(Criteria.where("tenantId").is(tenantId)
                        .and("projectId").is(projectId)
                        .and("mount").is(mount)),
                JaglanFolderState.class);
        outages.remove(outageKey(tenantId, projectId, mount));
    }

    // ─── internals ──────────────────────────────────────────────────────

    /**
     * Write (or refresh) one shell row.
     *
     * <p>Upsert through {@link MongoTemplate} rather than
     * {@code repository.save}: the id is derived, so a save would be an
     * <i>insert</i> the first time (version still null) and two pods listing
     * the same folder at once would collide on the primary key. The upsert
     * makes concurrent listings idempotent instead of racy.
     *
     * @param mountAccess what the mount as a whole allows — the fallback for
     *                    an entry that states no access of its own
     */
    private DocumentDocument upsertShell(
            String tenantId, String projectId, String mount,
            MountedStat stat, Duration ttl, MountAccess mountAccess, Instant now) {

        String path = JaglanPaths.documentPath(mount, stat.path());
        String id = JaglanPaths.documentId(tenantId, projectId, mount, stat.path());

        Update update = new Update()
                .set("tenantId", tenantId)
                .set("projectId", projectId)
                .set("path", path)
                .set("name", nameOf(path))
                // The source's preferred display name, when it has one. Set
                // rather than setOnInsert: a library that corrects a book's
                // title should see the correction, and nothing on our side
                // edits this field for a mounted row.
                .set("title", stat.title())
                .set("mimeType", stat.mimeType())
                .set("size", stat.size())
                .set("status", DocumentStatus.ACTIVE)
                .set("mountDirectory", stat.directory())
                // Freshness, not lifetime — see DocumentDocument.mountFreshUntil.
                // Using expiresAt here would hand the row to Mongo's TTL monitor,
                // and a purged row cannot be rebuilt from an id.
                .set("mountFreshUntil", now.plus(ttl))
                // Never a storageId — the absence of one is what marks the
                // content as living at the source.
                .unset("storageId")
                .set("compressed", false)
                // Belt to the claim queries' braces: those exclude _ext by
                // path, and these flags mean a row that somehow escapes the
                // path filter still does not enter the summary/RAG pipelines.
                .set("autoSummary", false)
                .set("summaryDirty", false)
                .set("ragEnabled", Boolean.FALSE)
                .set("ragDirty", false)
                .setOnInsert("createdAt", now)
                .setOnInsert("createdBy", "_jaglan")
                // Derived rather than random so a purged-and-rewritten row
                // keeps its identity; archives do not apply here anyway.
                .setOnInsert("lineageId", id)
                // Without this the first save() after an upsert would see a
                // null version and try to insert a row that already exists.
                .setOnInsert("version", 0L);

        // A read-only source is expressed through the existing soft lock, so
        // every write surface already refuses it with a message instead of
        // each one needing its own mount check.
        //
        // Asymmetric on purpose. Read-only is enforced on every refresh: it is
        // the source's standing answer, and a row that lost its lock between
        // two listings would be writable for as long as the window lasts. The
        // writable case only seeds the field, because there the value is the
        // *user's* — a lock set through PATCH /lock that vanished on the next
        // stat is exactly the "setting that is gone after a reload" §9 calls
        // worse than a rejection. The cost is a mount that flips RO → RW
        // keeping its locks until someone clears them; that direction is rare,
        // visible, recoverable in the UI, and errs towards refusing a write.
        if (effectiveAccess(stat, mountAccess) == MountAccess.RO) {
            update.set("lockedFor", MOUNT_LOCK);
        } else {
            update.setOnInsert("lockedFor", Set.of());
        }

        mongoTemplate.upsert(Query.query(Criteria.where("_id").is(id)),
                update, DocumentDocument.class);
        DocumentDocument row = mongoTemplate.findById(id, DocumentDocument.class);
        if (row == null) {
            // Only reachable if the row was removed between upsert and read;
            // synthesise rather than fail the caller's listing.
            row = DocumentDocument.builder()
                    .tenantId(tenantId).projectId(projectId)
                    .path(path).name(nameOf(path))
                    .mimeType(stat.mimeType()).size(stat.size())
                    .build();
            row.setId(id);
        }
        return row;
    }

    /** Whether a listing of this size is above the configured limit ({@code 0} = no limit). */
    private boolean isOversized(int entryCount) {
        return maxFolderEntries > 0 && entryCount > maxFolderEntries;
    }

    /**
     * Remove shell rows for direct children the source no longer reports.
     *
     * <p>A listing is authoritative for its own folder — without this, a file
     * deleted at the source keeps resolving from its cached row until the TTL
     * happens to expire, and reappears on every listing in between.
     */
    private void pruneVanished(
            String tenantId, String projectId, String mount, String folderInMount,
            List<MountedStat> entries) {

        Set<String> alive = new HashSet<>();
        for (MountedStat entry : entries) {
            alive.add(JaglanPaths.documentId(tenantId, projectId, mount, entry.path()));
        }
        for (DocumentDocument existing
                : readFolderRows(tenantId, projectId, mount, folderInMount)) {
            if (existing.getId() != null && !alive.contains(existing.getId())) {
                mongoTemplate.remove(
                        Query.query(Criteria.where("_id").is(existing.getId())),
                        DocumentDocument.class);
            }
        }
    }

    /** Direct children of a mount folder, from Mongo only. */
    private List<DocumentDocument> readFolderRows(
            String tenantId, String projectId, String mount, String folderInMount) {

        String folderPath = JaglanPaths.documentPath(mount, folderInMount);
        String childRegex = "^" + java.util.regex.Pattern.quote(folderPath + "/") + "[^/]+$";
        Query query = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId)
                .and("path").regex(childRegex));
        List<DocumentDocument> rows = mongoTemplate.find(query, DocumentDocument.class);
        List<DocumentDocument> out = new ArrayList<>(rows.size());
        for (DocumentDocument row : rows) {
            out.add(decorate(row, tenantId, projectId, mount));
        }
        return out;
    }

    private void writeFolderState(
            String stateId, String tenantId, String projectId, String mount, String folderInMount,
            @Nullable Instant listedAt, int entryCount, Duration ttl, @Nullable String failure) {

        Instant now = Instant.now();
        Update update = new Update()
                .set("tenantId", tenantId)
                .set("projectId", projectId)
                .set("mount", mount)
                .set("folder", JaglanPaths.normalizeInMountPath(folderInMount))
                .set("entryCount", entryCount)
                .set("expiresAt", now.plus(ttl));
        if (listedAt != null) {
            update.set("listedAt", listedAt);
        }
        if (failure == null) {
            update.unset("failedAt").unset("failureMessage");
        } else {
            update.set("failedAt", now)
                    .set("failureMessage", failure.length() > 500
                            ? failure.substring(0, 500) : failure);
        }
        mongoTemplate.upsert(Query.query(Criteria.where("_id").is(stateId)),
                update, JaglanFolderState.class);
    }

    /**
     * Fill the transient {@code mountAccess} field.
     *
     * <p>Done on the way out rather than persisted: the value belongs to the
     * source, and a mount that went read-only must not be described by a
     * number frozen into a row weeks ago.
     */
    /**
     * Fill {@code mountAccess} on a row fetched elsewhere — the by-id lookup,
     * which has no mount context of its own. Derives the mount from the path.
     */
    public void decorateAccess(DocumentDocument row) {
        try {
            decorate(row, row.getTenantId(), row.getProjectId(),
                    JaglanPaths.mountNameOf(row.getPath()));
        } catch (IllegalArgumentException e) {
            // Not a mounted path after all — nothing to describe.
            log.debug("No mount in path '{}': {}", row.getPath(), e.toString());
        }
    }

    private DocumentDocument decorate(
            DocumentDocument row, String tenantId, String projectId, String mount) {
        row.setMountAccess(findMount(tenantId, projectId, mount)
                .map(MountedSource::access)
                .orElse(MountAccess.UNKNOWN));
        return row;
    }

    private boolean isFresh(DocumentDocument row, Instant now) {
        // A stale row still answers — it is the only mapping from a derived id
        // back to a path. "Not fresh" means "re-stat before trusting the
        // metadata", never "pretend it is not there".
        return row.getMountFreshUntil() != null && row.getMountFreshUntil().isAfter(now);
    }

    private Duration ttlFor(String tenantId, String projectId, String mount) {
        return findMount(tenantId, projectId, mount)
                .map(MountedSource::metadataTtl)
                .orElse(MountedSource.DEFAULT_TTL);
    }

    /** What the mount as a whole allows, {@code UNKNOWN} when unresolvable. */
    private MountAccess accessOf(String tenantId, String projectId, String mount) {
        return findMount(tenantId, projectId, mount)
                .map(MountedSource::access)
                .orElse(MountAccess.UNKNOWN);
    }

    /**
     * The access that decides the lock: what the entry states, and when it
     * states nothing, what the mount states.
     *
     * <p>The fallback is load-bearing rather than tidy. Per-entry access is
     * not part of the {@code ode} wire contract — that side declares access
     * once for the whole source and every entry arrives {@code UNKNOWN} — so
     * without it a read-only {@code ode} library would lock nothing at all,
     * and the protection §6 promises would exist only for {@code local}.
     *
     * <p>{@code UNKNOWN} on both levels still stays writable: refusing a write
     * because the source was briefly unreachable would turn an outage into a
     * lock the user cannot explain. The source refuses at write time if it must.
     */
    private static MountAccess effectiveAccess(MountedStat stat, MountAccess mountAccess) {
        return stat.access() == MountAccess.UNKNOWN ? mountAccess : stat.access();
    }

    /**
     * {@code true} when this project has a resolvable mount list that does
     * <b>not</b> contain {@code mount}.
     *
     * <p>Deliberately not {@code !findMount(...).isPresent()}: that folds
     * "there are no mounts here" together with "we could not find out", and
     * the answer drives a delete. {@link #mounts} answers an empty list for a
     * process without Jaglan and for a broken configuration alike, so the
     * question is asked against the port directly and any doubt reads as
     * "leave the rows alone".
     */
    private boolean isUnconfigured(String tenantId, String projectId, String mount) {
        JaglanPort port = portProvider.getIfAvailable();
        if (port == null) return false;
        List<MountedSource> configured;
        try {
            configured = port.mounts(tenantId, projectId);
        } catch (RuntimeException e) {
            log.warn("Cannot tell whether mount '{}' is still configured in {}/{}: {}",
                    mount, tenantId, projectId, e.toString());
            return false;
        }
        for (MountedSource source : configured) {
            if (source.name().equals(mount)) return false;
        }
        return true;
    }

    private static String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String outageKey(String tenantId, String projectId, String mount) {
        return tenantId + '|' + projectId + '|' + mount;
    }

    private boolean isInOutage(String tenantId, String projectId, String mount, Instant now) {
        Instant until = outages.get(outageKey(tenantId, projectId, mount));
        if (until == null) return false;
        if (until.isAfter(now)) return true;
        outages.remove(outageKey(tenantId, projectId, mount));
        return false;
    }

    private void rememberOutage(
            String tenantId, String projectId, String mount, Instant now, RuntimeException cause) {
        outages.put(outageKey(tenantId, projectId, mount),
                now.plusSeconds(outageMemorySeconds));
        log.warn("Mount '{}' in {}/{} failed — muted for {}s: {}",
                mount, tenantId, projectId, outageMemorySeconds, cause.toString());
    }
}
