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

    /** Key is {@code tenant|project|mount}; value is when to try again. */
    private final Map<String, Instant> outages = new ConcurrentHashMap<>();

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
        DocumentDocument row = upsertShell(tenantId, projectId, mount, stat.get(), ttl, now);
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

        boolean usable = !force && state != null && state.isFresh(now);
        if (!usable && !isInOutage(tenantId, projectId, mount, now)) {
            JaglanPort port = portProvider.getIfAvailable();
            if (port != null) {
                try {
                    List<MountedStat> entries =
                            port.list(tenantId, projectId, mount, folderInMount);
                    Duration ttl = ttlFor(tenantId, projectId, mount);
                    for (MountedStat entry : entries) {
                        upsertShell(tenantId, projectId, mount, entry, ttl, now);
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
                documents = countKnownDescendants(tenantId, projectId, source.name());
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

    private int countKnownDescendants(String tenantId, String projectId, String mount) {
        String prefix = JaglanPaths.mountRootPath(mount) + "/";
        return (int) mongoTemplate.count(
                Query.query(Criteria.where("tenantId").is(tenantId)
                        .and("projectId").is(projectId)
                        .and("path").regex("^" + java.util.regex.Pattern.quote(prefix))),
                DocumentDocument.class);
    }

    private int countKnownRootSubfolders(String tenantId, String projectId, String mount) {
        int subfolders = 0;
        for (DocumentDocument row : readFolderRows(tenantId, projectId, mount, "")) {
            // A shell row for a directory carries no mime type and no size —
            // the same shape MountedStat enforces for directories.
            if (row.getMimeType() == null && row.getSize() == 0) subfolders++;
        }
        return subfolders;
    }

    /** {@code true} when this folder has been listed at least once. */
    public boolean isFolderKnown(
            String tenantId, String projectId, String mount, String folderInMount) {
        JaglanFolderState state = mongoTemplate.findById(
                JaglanPaths.folderStateId(tenantId, projectId, mount, folderInMount),
                JaglanFolderState.class);
        return state != null && state.getListedAt() != null;
    }

    /** Drop every shell row and folder marker of a mount — used when a mount
     *  is removed from the configuration, and by the explicit full refresh. */
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
     */
    private DocumentDocument upsertShell(
            String tenantId, String projectId, String mount,
            MountedStat stat, Duration ttl, Instant now) {

        String path = JaglanPaths.documentPath(mount, stat.path());
        String id = JaglanPaths.documentId(tenantId, projectId, mount, stat.path());

        Update update = new Update()
                .set("tenantId", tenantId)
                .set("projectId", projectId)
                .set("path", path)
                .set("name", nameOf(path))
                .set("mimeType", stat.mimeType())
                .set("size", stat.size())
                .set("status", DocumentStatus.ACTIVE)
                .set("expiresAt", now.plus(ttl))
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
                // A read-only source is expressed through the existing soft
                // lock, so every write surface already refuses it with a
                // message instead of each one needing its own mount check.
                .set("lockedFor", lockFor(stat.access()))
                .setOnInsert("createdAt", now)
                .setOnInsert("createdBy", "_jaglan")
                // Derived rather than random so a purged-and-rewritten row
                // keeps its identity; archives do not apply here anyway.
                .setOnInsert("lineageId", id)
                // Without this the first save() after an upsert would see a
                // null version and try to insert a row that already exists.
                .setOnInsert("version", 0L);

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
    private DocumentDocument decorate(
            DocumentDocument row, String tenantId, String projectId, String mount) {
        row.setMountAccess(findMount(tenantId, projectId, mount)
                .map(MountedSource::access)
                .orElse(MountAccess.UNKNOWN));
        return row;
    }

    private boolean isFresh(DocumentDocument row, Instant now) {
        // The application checks, not Mongo's TTL monitor — that runs about
        // once a minute, so an expired row can still be readable. Same rule
        // OAuthStateService applies to its states.
        return row.getExpiresAt() != null && row.getExpiresAt().isAfter(now);
    }

    private Duration ttlFor(String tenantId, String projectId, String mount) {
        return findMount(tenantId, projectId, mount)
                .map(MountedSource::metadataTtl)
                .orElse(MountedSource.DEFAULT_TTL);
    }

    private static Set<WriterRole> lockFor(MountAccess access) {
        // RW and UNKNOWN both stay writable: refusing a write because the
        // source was briefly unreachable would turn an outage into a lock the
        // user cannot explain. The source refuses at write time if it must.
        return access == MountAccess.RO
                ? Set.of(WriterRole.AI, WriterRole.USER, WriterRole.KIT)
                : Set.of();
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
