package de.mhus.vance.shared.document;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.storage.StorageService;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Document lifecycle, lookup and content handling — the one entry point to
 * document data.
 *
 * <p>On {@link #create} the service decides whether the payload fits in the
 * document itself or should be offloaded to
 * {@link StorageService}. Text content smaller than
 * {@code vance.document.inline-threshold} (default 4096 bytes) goes inline;
 * everything else streams to storage. {@link #loadContent} hides the split.
 *
 * <p>Folders are not stored — {@link #extractFolders} derives them from the
 * {@code path} field of all documents in a project.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    /**
     * Classpath prefix for system-default documents shipped inside the
     * brain JAR — e.g. {@code vance-defaults/agent.md}. The cascade
     * lookup falls through to this layer when neither the user's
     * project nor the tenant-wide {@code _vance} project carries the
     * requested path.
     */
    public static final String RESOURCE_PREFIX = "vance-defaults/";

    private final DocumentRepository repository;
    private final StorageService storageService;
    private final MongoTemplate mongoTemplate;
    private final ResourcePatternResolver resourcePatternResolver;
    private final DocumentHeaderParser headerParser;
    private final DocumentArchiveService archiveService;
    private final de.mhus.vance.shared.settings.SettingService settingService;

    @Value("${vance.document.inline-threshold:40960}")
    private int inlineThreshold;

    /**
     * Publisher for {@link DocumentChangedEvent}. Field-injected (instead of
     * via the {@link RequiredArgsConstructor}) so the existing 7-arg
     * test-constructor callers stay compilable; tests that don't wire a
     * Spring context simply leave this {@code null} and the publish helper
     * becomes a no-op.
     */
    @Autowired(required = false)
    private @Nullable ApplicationEventPublisher eventPublisher;

    /**
     * Master switch for gzip-compressing document blobs on write. When
     * {@code false} all writes bypass the compression branch even if their
     * size would exceed the threshold. Reads always honour the per-document
     * {@code compressed} flag, so flipping this off does not break access to
     * existing compressed payloads.
     */
    @Value("${vance.document.compression.enabled:true}")
    private boolean compressionEnabled;

    /**
     * Byte threshold below which a document's content is stored uncompressed.
     * Markdown / YAML / JSON tends to compress 3-5× but gzip's framing
     * overhead (≈20 bytes) makes it counter-productive for tiny payloads;
     * the default matches the value Nimbus has running in production.
     */
    @Value("${vance.document.compression.threshold:1000}")
    private int compressionThreshold;

    /**
     * Daemon pool for the streaming-gzip path. Each large-write spawns one
     * task that pumps the source stream through a {@link GZIPOutputStream}
     * into a {@link PipedOutputStream}; the storage layer reads the matching
     * {@link PipedInputStream}. {@code newCachedThreadPool} keeps idle threads
     * around briefly for the next write — matches Nimbus' production setup.
     */
    private final ExecutorService compressionExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("doc-compression-" + t.getId());
        return t;
    });

    @PreDestroy
    void shutdownCompressionExecutor() {
        compressionExecutor.shutdown();
    }

    /**
     * Outcome of a single streaming write into {@link StorageService}. Carries
     * the data we need to mirror onto the {@link DocumentDocument}: storage id,
     * gzip flag, and original (uncompressed) byte count.
     */
    public record ContentWriteResult(String storageId, boolean compressed, long originalSize) {}

    /**
     * Operator-level kill-switch for document versioning. When {@code false},
     * archives are never created regardless of the per-project / tenant
     * setting cascade. The per-project {@code documents.archive.enabled}
     * cascade setting (default {@code true}) layers on top.
     */
    @Value("${vance.documents.archive.enabled:true}")
    private boolean archiveEnabledDefault;

    /**
     * Minimum interval between two archive entries for the same document.
     * Saves within this window collapse — the running edit-burst becomes
     * a single archived version timestamped at the first save. Configured
     * in {@code application.yml}, overridable per project via the
     * {@value #SETTING_ARCHIVE_MIN_INTERVAL_SECONDS} cascade setting.
     */
    @Value("${vance.documents.archive.minVersionIntervalSeconds:600}")
    private long archiveMinIntervalSecondsDefault;

    /** Per-project cascade setting: opt-out for the archive feature. */
    public static final String SETTING_ARCHIVE_ENABLED = "documents.archive.enabled";

    /** Per-project cascade setting: minimum seconds between archive entries. */
    public static final String SETTING_ARCHIVE_MIN_INTERVAL_SECONDS =
            "documents.archive.minVersionIntervalSeconds";

    public Optional<DocumentDocument> findById(String id) {
        return repository.findById(id);
    }

    public Optional<DocumentDocument> findByPath(String tenantId, String projectId, String path) {
        return repository.findByTenantIdAndProjectIdAndPath(tenantId, projectId, normalizePath(path));
    }

    /**
     * Decode a document's body as UTF-8 text, transparently handling both
     * inline payloads and storage-backed blobs. Public hand-off of the
     * same helper {@link #lookupCascade} uses internally — exposed so
     * callers that walk specific layers (e.g. {@link de.mhus.vance.brain.ai.ModelCatalog}
     * doing deep-merge across all three) can read content without
     * re-implementing the inline-vs-storage branch.
     *
     * <p>Returns the empty string on read failure (logged) rather than
     * propagating; the alternative would force every caller into a
     * try/catch for a corner case that already produces a WARN line.
     */
    public String readContent(DocumentDocument doc) {
        return readAsString(doc);
    }

    /** All {@link DocumentStatus#ACTIVE} documents in the project. */
    public List<DocumentDocument> listByProject(String tenantId, String projectId) {
        return repository.findByTenantIdAndProjectIdAndStatus(tenantId, projectId, DocumentStatus.ACTIVE);
    }

    /**
     * Page through {@link DocumentStatus#ACTIVE} documents in the project,
     * sorted by {@code path} ascending so the order is deterministic across
     * pages. {@code page} is zero-based.
     */
    public Page<DocumentDocument> listByProjectPaged(
            String tenantId, String projectId, int page, int size) {
        return listByProjectPaged(tenantId, projectId, page, size, null);
    }

    /**
     * Page through documents with an optional path-prefix filter — the
     * UI uses this to scope the list to a folder selected (or freely
     * typed) in the path-filter combobox. {@code pathPrefix} is matched
     * with a regex-anchored {@code startsWith} so {@code "notes/"}
     * returns everything inside the folder and {@code "notes/draft"}
     * narrows to that prefix specifically. {@code null} or blank →
     * unfiltered (same as the no-prefix overload).
     */
    public Page<DocumentDocument> listByProjectPaged(
            String tenantId, String projectId, int page, int size,
            @Nullable String pathPrefix) {
        return listByProjectPaged(tenantId, projectId, page, size, pathPrefix, null);
    }

    /**
     * Page through documents with optional path-prefix and {@code kind}
     * filters. The {@code kind} comes from parsed front matter of markdown
     * documents (see {@link DocumentHeaderParser}); passing it scopes the
     * list to documents whose body declared {@code kind: <value>}.
     */
    public Page<DocumentDocument> listByProjectPaged(
            String tenantId, String projectId, int page, int size,
            @Nullable String pathPrefix, @Nullable String kind) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by("path").ascending());

        String trimmedPrefix = (pathPrefix == null || pathPrefix.isBlank()) ? null : pathPrefix.trim();
        if (trimmedPrefix != null) {
            // Strip a leading slash so "/notes" and "notes" match the same
            // documents — paths are stored without the leading slash.
            while (trimmedPrefix.startsWith("/")) trimmedPrefix = trimmedPrefix.substring(1);
        }
        String trimmedKind = (kind == null || kind.isBlank()) ? null : kind.trim();

        // Hide the trash folder by default — soft-deleted documents
        // live under {@value #TRASH_FOLDER_PREFIX} and would otherwise
        // pollute every default listing. Callers that want to inspect
        // the trash pass {@code pathPrefix="_bin/"} explicitly, which
        // skips this filter (the prefix narrows the query to exactly
        // the trash subtree).
        Query query = new Query()
                .addCriteria(Criteria.where("tenantId").is(tenantId))
                .addCriteria(Criteria.where("projectId").is(projectId))
                .addCriteria(Criteria.where("status").is(DocumentStatus.ACTIVE))
                .with(pageable);
        if (trimmedKind != null) {
            query.addCriteria(Criteria.where("kind").is(trimmedKind));
        }
        if (trimmedPrefix != null) {
            query.addCriteria(Criteria.where("path").regex("^" + java.util.regex.Pattern.quote(trimmedPrefix)));
        } else {
            query.addCriteria(Criteria.where("path")
                    .not()
                    .regex("^" + java.util.regex.Pattern.quote(TRASH_FOLDER_PREFIX)));
        }
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), DocumentDocument.class);
        List<DocumentDocument> content = mongoTemplate.find(query, DocumentDocument.class);
        return new org.springframework.data.domain.PageImpl<>(content, pageable, total);
    }

    /**
     * Folder-view listing for the documents UI: subfolders directly
     * under {@code path} plus a paged list of files in that same
     * directory (one level deep only, not recursive).
     *
     * <p>Path normalisation:
     * <ul>
     *   <li>{@code null} / blank → list the project root.</li>
     *   <li>leading {@code /} stripped (paths are stored without).</li>
     *   <li>missing trailing {@code /} appended so the regex pivots
     *       on a real segment boundary.</li>
     * </ul>
     *
     * <p>The trash folder ({@value #TRASH_FOLDER_PREFIX}) is excluded
     * from both folders and files unless the caller explicitly browses
     * inside it — same convention as {@link #listByProjectPaged}.
     */
    public FolderListing listByFolder(
            String tenantId, String projectId, @Nullable String path,
            @Nullable String search, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String prefix = normalizeFolderPrefix(path);
        String needle = (search == null || search.isBlank()) ? null : search.trim();

        // ─── Files: paths starting with prefix and containing no further slash.
        // No explicit trash-exclusion needed: the {@code [^/]+$} tail
        // already rejects anything that nests further (including
        // {@code _bin/foo}), and the only way to see trash files is to
        // explicitly browse with {@code prefix = "_bin/"}.
        //
        // When a search needle is set we layer an OR(path, title)
        // substring match via {@code andOperator} so the path-shape
        // anchor and the search criteria don't both try to add a
        // top-level {@code path} field on the Query.
        String filesRegex = "^" + java.util.regex.Pattern.quote(prefix) + "[^/]+$";
        Criteria fileCriteria;
        if (needle == null) {
            fileCriteria = Criteria.where("path").regex(filesRegex);
        } else {
            String needleRegex = java.util.regex.Pattern.quote(needle);
            fileCriteria = new Criteria().andOperator(
                    Criteria.where("path").regex(filesRegex),
                    new Criteria().orOperator(
                            Criteria.where("path").regex(needleRegex, "i"),
                            Criteria.where("title").regex(needleRegex, "i")));
        }
        Query filesQuery = baseProjectQuery(tenantId, projectId)
                .addCriteria(fileCriteria)
                .with(PageRequest.of(safePage, safeSize, Sort.by("path").ascending()));
        long totalFiles = mongoTemplate.count(
                Query.of(filesQuery).limit(-1).skip(-1), DocumentDocument.class);
        List<DocumentDocument> files = mongoTemplate.find(filesQuery, DocumentDocument.class);

        // ─── Folders: distinct first segments of paths that nest
        // beyond the prefix. Aggregation pipeline:
        //   1. Match: documents in this project under the prefix.
        //   2. Project: strip the prefix from path → keep the remainder.
        //   3. Match: remainder must contain at least one slash.
        //   4. Project: first slash-segment of the remainder.
        //   5. Group + sort: distinct folder names, alphabetical.
        java.util.List<org.bson.Document> pipeline = new java.util.ArrayList<>();
        org.bson.Document match = new org.bson.Document()
                .append("tenantId", tenantId)
                .append("projectId", projectId)
                .append("status", DocumentStatus.ACTIVE.name())
                .append("path", new org.bson.Document(
                        "$regex", "^" + java.util.regex.Pattern.quote(prefix)));
        if (prefix.isEmpty()) {
            // Same trash-exclusion as the file query.
            match.append("$nor", List.of(new org.bson.Document(
                    "path",
                    new org.bson.Document("$regex",
                            "^" + java.util.regex.Pattern.quote(TRASH_FOLDER_PREFIX)))));
        }
        pipeline.add(new org.bson.Document("$match", match));
        // {@code $substr} of (path, prefixLen, -1) returns the suffix
        // after the prefix. With prefixLen=0 (root) that's the full
        // path; with prefix=`documents/` and path=`documents/notes/x`
        // we get `notes/x`.
        pipeline.add(new org.bson.Document("$project", new org.bson.Document(
                "rest", new org.bson.Document("$substr",
                        java.util.List.of("$path", prefix.length(), -1)))));
        pipeline.add(new org.bson.Document("$match", new org.bson.Document(
                "rest", new org.bson.Document("$regex", "/"))));
        pipeline.add(new org.bson.Document("$project", new org.bson.Document(
                "folder", new org.bson.Document("$arrayElemAt",
                        java.util.List.of(new org.bson.Document(
                                "$split", java.util.List.of("$rest", "/")), 0)))));
        pipeline.add(new org.bson.Document("$group", new org.bson.Document("_id", "$folder")));
        if (needle != null) {
            // Filter folder names by the same search needle (case-
            // insensitive substring on the folder segment itself).
            pipeline.add(new org.bson.Document("$match", new org.bson.Document(
                    "_id", new org.bson.Document()
                            .append("$regex", java.util.regex.Pattern.quote(needle))
                            .append("$options", "i"))));
        }
        pipeline.add(new org.bson.Document("$sort", new org.bson.Document("_id", 1)));

        List<String> folders = new ArrayList<>();
        for (org.bson.Document doc : mongoTemplate.getCollection("documents")
                .aggregate(pipeline).allowDiskUse(true)) {
            Object id = doc.get("_id");
            if (id instanceof String s && !s.isBlank()) folders.add(s);
        }

        return new FolderListing(folders, files, safePage, safeSize, totalFiles);
    }

    private static String normalizeFolderPrefix(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.startsWith("/")) s = s.substring(1);
        if (s.isEmpty()) return "";
        if (!s.endsWith("/")) s = s + "/";
        return s;
    }

    private Query baseProjectQuery(String tenantId, String projectId) {
        return new Query()
                .addCriteria(Criteria.where("tenantId").is(tenantId))
                .addCriteria(Criteria.where("projectId").is(projectId))
                .addCriteria(Criteria.where("status").is(DocumentStatus.ACTIVE));
    }

    /** Return shape for {@link #listByFolder}. */
    public record FolderListing(
            List<String> folders,
            List<DocumentDocument> files,
            int page,
            int pageSize,
            long totalFiles) {}

    /** All {@link DocumentStatus#ACTIVE} documents in the project that declared {@code kind: <kind>}. */
    public List<DocumentDocument> listByKind(String tenantId, String projectId, String kind) {
        return repository.findByTenantIdAndProjectIdAndStatusAndKind(
                tenantId, projectId, DocumentStatus.ACTIVE, kind);
    }

    /**
     * Distinct {@code kind} values present in the project. Lightweight —
     * Mongo projection on the indexed {@code kind} field. Sorted, with
     * {@code null}/blank dropped (documents without a {@code kind} header
     * are not part of this projection).
     */
    public List<String> listKinds(String tenantId, String projectId) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId)
                .and("status").is(DocumentStatus.ACTIVE)
                .and("kind").ne(null));
        List<String> kinds = mongoTemplate.findDistinct(
                query, "kind", DocumentDocument.class, String.class);
        TreeSet<String> sorted = new TreeSet<>();
        for (String k : kinds) {
            if (k != null && !k.isBlank()) sorted.add(k);
        }
        return new ArrayList<>(sorted);
    }

    /**
     * Returns the list of unique folder paths that contain at least
     * one active document in this project. Sorted alphabetically.
     * Implementation reads <em>only the path field</em> via the
     * {@link MongoTemplate#findDistinct} projection — the rest of
     * the document doesn't load. Folders are then derived in-process
     * by splitting each path at {@code "/"}.
     *
     * <p>The empty-string entry (top-level documents not nested in
     * any folder) is intentionally omitted; the UI shows "(all)" /
     * blank input for that case.
     */
    public List<String> listFolders(String tenantId, String projectId) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId)
                .and("status").is(DocumentStatus.ACTIVE));
        List<String> paths = mongoTemplate.findDistinct(
                query, "path", DocumentDocument.class, String.class);
        TreeSet<String> folders = new TreeSet<>();
        for (String p : paths) {
            for (String f : foldersOfPath(p)) {
                folders.add(f);
            }
        }
        return new ArrayList<>(folders);
    }

    /** All {@link DocumentStatus#ACTIVE} documents in the project that carry {@code tag}. */
    public List<DocumentDocument> listByTag(String tenantId, String projectId, String tag) {
        return repository.findByTenantIdAndProjectIdAndTagsContainingAndStatus(
                tenantId, projectId, tag, DocumentStatus.ACTIVE);
    }

    /**
     * Streams {@code content} into {@link StorageService}, applying the same
     * compression strategy Nimbus runs in production:
     *
     * <ul>
     *   <li>Probe the first {@code (threshold + 1)} bytes into a buffer.
     *       When the stream finishes inside the buffer (small payload) we
     *       hand it to storage as-is.</li>
     *   <li>When the payload exceeds the threshold and compression is on,
     *       we spawn a background task that feeds the buffer + rest of the
     *       source through {@link GZIPOutputStream} into a
     *       {@link PipedOutputStream}; the storage layer reads the matching
     *       {@link PipedInputStream}. No buffering of the full payload.</li>
     *   <li>When the payload exceeds the threshold but compression is off
     *       (or we are storing a small file that simply happens to be a
     *       binary), we stitch the buffer back onto the source via
     *       {@link SequenceInputStream} and wrap with a counting
     *       {@link FilterInputStream} so we still know the byte total when
     *       storage returns.</li>
     * </ul>
     *
     * <p>The caller still owns the source {@link InputStream} and must close
     * it after this method returns (or after the consuming storage call has
     * drained it, in the streaming-piped case the storage layer takes care
     * of that on its end).
     */
    ContentWriteResult streamingStoreContent(String tenantId, String path, InputStream content) {
        int probeLimit = Math.max(compressionThreshold + 1, 1);
        byte[] initialBuffer = new byte[probeLimit];
        int bytesRead;
        try {
            bytesRead = content.readNBytes(initialBuffer, 0, probeLimit);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read document content for storage", e);
        }

        AtomicLong totalBytes = new AtomicLong(bytesRead);
        InputStream finalStream;
        boolean willCompress;

        if (bytesRead > compressionThreshold && compressionEnabled) {
            // Streaming-gzip path: buffer + remainder flow through a piped
            // GZIPOutputStream in a background thread; the storage layer
            // reads the corresponding PipedInputStream. Memory footprint
            // is bounded by the 64 KB pipe buffer.
            PipedInputStream pipedIn;
            PipedOutputStream pipedOut;
            try {
                pipedIn = new PipedInputStream(64 * 1024);
                pipedOut = new PipedOutputStream(pipedIn);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create compression pipe", e);
            }
            final int initialBytesRead = bytesRead;
            compressionExecutor.submit(() -> {
                try (GZIPOutputStream gzip = new GZIPOutputStream(pipedOut)) {
                    gzip.write(initialBuffer, 0, initialBytesRead);
                    byte[] chunk = new byte[8 * 1024];
                    int n;
                    while ((n = content.read(chunk)) > 0) {
                        gzip.write(chunk, 0, n);
                        totalBytes.addAndGet(n);
                    }
                    gzip.finish();
                } catch (Exception e) {
                    log.error("Failed to compress document content for path='{}'", path, e);
                    // Closing the pipe makes the storage-side reader observe EOF
                    // (and the partial write fails at the StorageService level).
                    try { pipedOut.close(); } catch (Exception ignored) { /* best effort */ }
                }
            });
            finalStream = pipedIn;
            willCompress = true;
        } else if (bytesRead > compressionThreshold) {
            // Large payload, compression off: stitch buffer + rest, count bytes
            // as storage drains the stream.
            ByteArrayInputStream initialStream = new ByteArrayInputStream(initialBuffer, 0, bytesRead);
            InputStream sequenced = new SequenceInputStream(initialStream, content);
            finalStream = new FilterInputStream(sequenced) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b != -1) totalBytes.incrementAndGet();
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) totalBytes.addAndGet(n);
                    return n;
                }
            };
            // totalBytes was seeded with initialBuffer's bytes; sequence reads
            // them again here, so reset to avoid double-counting.
            totalBytes.set(0);
            willCompress = false;
        } else {
            // Small payload: everything fit in initialBuffer; totalBytes is
            // already correct.
            finalStream = new ByteArrayInputStream(initialBuffer, 0, bytesRead);
            willCompress = false;
        }

        StorageService.StorageInfo info = storageService.store(tenantId, path, finalStream);
        return new ContentWriteResult(info.id(), willCompress, totalBytes.get());
    }

    /**
     * Creates a document. {@code content} is consumed; close it yourself if you opened it.
     *
     * <p>Equivalent to calling
     * {@link #create(String, String, String, String, List, String, InputStream, String, Boolean, Boolean)}
     * with {@code null} for both override flags — auto-summary follows the
     * mime-eligibility default and RAG-inclusion follows the
     * path/mime-eligibility default.
     *
     * @throws DocumentAlreadyExistsException if a document with the same {@code path}
     *         already exists in {@code (tenantId, projectId)}.
     */
    public DocumentDocument create(
            String tenantId,
            String projectId,
            String path,
            @Nullable String title,
            @Nullable List<String> tags,
            @Nullable String mimeType,
            InputStream content,
            @Nullable String createdBy) {
        return create(tenantId, projectId, path, title, tags, mimeType, content, createdBy,
                /*autoSummaryOverride*/ null, /*ragEnabledOverride*/ null);
    }

    /**
     * Creates a document with explicit overrides for the auto-summary and
     * project-RAG flags. Intended for auto-generated artefacts (chat
     * exports, snapshots, hook outputs) whose content is duplicative of
     * material already summarised / indexed elsewhere.
     *
     * <p>Semantics:
     * <ul>
     *   <li>{@code autoSummaryOverride}: {@code null} = mime-eligibility default;
     *       {@code true}/{@code false} = explicit opt-in/opt-out.</li>
     *   <li>{@code ragEnabledOverride}: {@code null} = path/mime-eligibility default;
     *       {@code true}/{@code false} = explicit override stored on the document
     *       (see {@link #isRagEligible(DocumentDocument)}). When {@code false},
     *       {@code ragDirty} stays {@code false} so the indexer never enqueues
     *       a first embed run.</li>
     * </ul>
     *
     * @throws DocumentAlreadyExistsException if a document with the same {@code path}
     *         already exists in {@code (tenantId, projectId)}.
     */
    public DocumentDocument create(
            String tenantId,
            String projectId,
            String path,
            @Nullable String title,
            @Nullable List<String> tags,
            @Nullable String mimeType,
            InputStream content,
            @Nullable String createdBy,
            @Nullable Boolean autoSummaryOverride,
            @Nullable Boolean ragEnabledOverride) {

        String normalizedPath = normalizePath(path);
        if (repository.existsByTenantIdAndProjectIdAndPath(tenantId, projectId, normalizedPath)) {
            throw new DocumentAlreadyExistsException(
                    "Document '" + normalizedPath + "' already exists in "
                            + tenantId + "/" + projectId);
        }

        ContentWriteResult write = streamingStoreContent(tenantId, normalizedPath, content);

        boolean autoSummary = autoSummaryOverride != null
                ? autoSummaryOverride
                : isAutoSummaryEligible(mimeType);

        DocumentDocument doc = DocumentDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .path(normalizedPath)
                .name(extractName(normalizedPath))
                .title(title)
                .tags(tags == null ? new ArrayList<>() : new ArrayList<>(tags))
                .mimeType(mimeType)
                .size(write.originalSize())
                .storageId(write.storageId())
                .compressed(write.compressed())
                .createdBy(createdBy)
                .status(DocumentStatus.ACTIVE)
                .autoSummary(autoSummary)
                .ragEnabled(ragEnabledOverride)
                .lineageId(java.util.UUID.randomUUID().toString())
                .build();
        // Header parsing reads back through loadContent → the just-written
        // storage blob. One extra round-trip per create, but it keeps the
        // streaming-write path branch-free.
        applyHeader(doc);
        // isRagEligible respects the override set above: if ragEnabled=false
        // it returns false and we never enqueue an embed run.
        doc.setRagDirty(isRagEligible(doc));

        DocumentDocument saved = repository.save(doc);
        log.info("Created document tenantId='{}' projectId='{}' path='{}' id='{}' compressed={} size={}",
                saved.getTenantId(), saved.getProjectId(), saved.getPath(), saved.getId(),
                saved.isCompressed(), saved.getSize());
        return publishUpserted(saved);
    }

    /** Convenience for text payloads that are known to be in-memory.
     *  MIME is derived from the path extension (.md → text/markdown,
     *  .yaml/.yml → application/yaml, .json → application/json,
     *  everything else → text/plain) so kind-codecs and header
     *  strategies pick the right parser. */
    public DocumentDocument createText(
            String tenantId,
            String projectId,
            String path,
            @Nullable String title,
            @Nullable List<String> tags,
            String text,
            @Nullable String createdBy) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return create(tenantId, projectId, path, title, tags,
                mimeFromPath(path),
                new ByteArrayInputStream(bytes),
                createdBy);
    }

    /** Map file extension to a canonical text MIME. Falls back to
     *  {@code text/plain} for unknown extensions or paths without
     *  one — matches what kind-codecs / header strategies expect
     *  (see {@link MarkdownHeaderStrategy}, {@link YamlHeaderStrategy},
     *  {@link JsonHeaderStrategy}). Public so the controllers can
     *  reuse the same mapping when a request omits {@code mimeType}. */
    public static String mimeFromPath(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "text/plain";
        String ext = name.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "md", "markdown" -> "text/markdown";
            case "yaml", "yml" -> "application/yaml";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "csv" -> "text/csv";
            // Code / script extensions — the Web-UI's CodeEditor maps
            // these mime-types to syntax-highlighting languages.
            case "js", "mjs", "cjs", "mjsh" -> "text/javascript";
            case "ts", "tsx" -> "text/typescript";
            case "py" -> "text/x-python";
            case "sh", "bash" -> "text/x-shellscript";
            case "r" -> "text/x-r";
            case "java" -> "text/x-java";
            case "sql" -> "application/sql";
            default -> "text/plain";
        };
    }

    /**
     * Create-or-replace text-content by path. If a document at
     * {@code path} already exists in this project, update its
     * text in place — the inline/storage transition is handled
     * transparently by {@link #update}, so growth past the
     * inline threshold cleanly moves the doc to storage-backed
     * (and shrinkage back to inline). Otherwise create a new
     * document. Idempotent — useful for callers that re-emit the
     * same logical artifact across retries.
     */
    public DocumentDocument upsertText(
            String tenantId,
            String projectId,
            String path,
            @Nullable String title,
            @Nullable List<String> tags,
            String text,
            @Nullable String createdBy) {
        Optional<DocumentDocument> existing = findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            return update(existing.get().getId(), title, tags, text, null);
        }
        return createText(tenantId, projectId, path, title, tags,
                text, createdBy);
    }

    /**
     * {@link #upsertText} variant that sets {@link DocumentDocument#getExpiresAt()}
     * so MongoDB's TTL monitor reaps the row at the given timestamp.
     * Intended for ephemeral diagnostics (scheduler run logs, UrsaEvent
     * trigger logs); normal documents must not set this — passing
     * {@code null} disables expiry (and matches the {@link #upsertText}
     * behaviour exactly).
     *
     * <p>Ephemeral docs are also force-excluded from the auto-summary
     * scheduler and the project-RAG indexer regardless of mime type or
     * path: writing one log entry per trigger would otherwise burn LLM
     * quota on machine-generated YAML that no human reads through summary
     * or semantic search.
     *
     * <p>Costs one extra {@code save} on top of {@link #upsertText} —
     * the underlying upsert API doesn't currently take an expiry, and
     * writing through that path keeps lineage and header application
     * consistent with every other document.
     */
    public DocumentDocument upsertEphemeralText(
            String tenantId,
            String projectId,
            String path,
            @Nullable String title,
            @Nullable List<String> tags,
            String text,
            @Nullable String createdBy,
            @Nullable Instant expiresAt) {
        DocumentDocument doc = upsertText(tenantId, projectId, path, title, tags, text, createdBy);
        boolean dirty = false;
        if (!java.util.Objects.equals(doc.getExpiresAt(), expiresAt)) {
            doc.setExpiresAt(expiresAt);
            dirty = true;
        }
        if (doc.isAutoSummary()) {
            doc.setAutoSummary(false);
            dirty = true;
        }
        if (doc.isSummaryDirty()) {
            doc.setSummaryDirty(false);
            dirty = true;
        }
        if (!Boolean.FALSE.equals(doc.getRagEnabled())) {
            doc.setRagEnabled(Boolean.FALSE);
            dirty = true;
        }
        if (doc.isRagDirty()) {
            doc.setRagDirty(false);
            dirty = true;
        }
        if (dirty) {
            doc = repository.save(doc);
        }
        return doc;
    }

    /**
     * Replace a document's content from a stream — the streaming counterpart
     * the web/mobile editors call when saving. Mirrors the content-branch of
     * {@link #update}: archives the previous version (subject to the
     * cascade + min-interval gates), writes the new blob through
     * {@link #streamingStoreContent}, rewrites the {@code storageId} /
     * {@code compressed} / {@code size} fields, deletes the previous blob
     * (unless it was just transferred to an archive), re-parses headers,
     * and flips {@code summaryDirty} / {@code ragDirty}.
     *
     * <p>Optional {@code newMimeType} updates the mime type before the
     * header parse so the right strategy runs against the new body.
     * Pass {@code null} to leave the mime untouched.
     *
     * @return the updated document
     * @throws IllegalArgumentException if the document is unknown
     */
    public DocumentDocument replaceContent(
            String id,
            InputStream content,
            @Nullable String newMimeType) {
        return replaceContent(id, content, newMimeType, TOOL_IDENTITY);
    }

    /**
     * Single-editorId-overload — used by callers that only have the
     * editorId on hand (no user identity). Translates to a
     * {@link WriterIdentity} with null user fields.
     */
    public DocumentDocument replaceContent(
            String id,
            InputStream content,
            @Nullable String newMimeType,
            @Nullable String editorId) {
        return replaceContent(id, content, newMimeType,
                WriterIdentity.of(editorId, null, null));
    }

    /**
     * Same as {@link #replaceContent(String, InputStream, String)} but
     * with full {@link WriterIdentity} — used by REST controllers that
     * have a JWT-bound user and the X-Editor-Id header. Forwarded into
     * the live-broadcast event so the writer's own WebSocket can be
     * skipped during local fan-out and subscribers can render the
     * {@code ⏺ name} awareness badge.
     */
    public DocumentDocument replaceContent(
            String id,
            InputStream content,
            @Nullable String newMimeType,
            WriterIdentity identity) {
        DocumentDocument doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown document id='" + id + "'"));

        // Buffer the new body so we can short-circuit no-op writes. The
        // editor's auto-save in Cortex fires on bare clicks too, sending
        // a PUT with byte-identical content; without this gate every
        // click would spin a fresh storage blob, an archive (if eligible),
        // a Mongo save, and a documents.changed broadcast. Costs one heap
        // allocation per save — same memory profile as the streamed path,
        // because the streaming-store layer reads the stream into a
        // ByteArrayOutputStream further down anyway when gzip is in play.
        byte[] newBytes;
        try {
            newBytes = content.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to buffer new content for id='" + id + "'", e);
        }

        boolean mimeChanged = newMimeType != null
                && !newMimeType.isBlank()
                && !newMimeType.equals(doc.getMimeType());
        boolean contentChanged = isContentDifferent(doc, newBytes);
        if (!mimeChanged && !contentChanged) {
            log.debug("Skipping no-op content replace tenantId='{}' projectId='{}' path='{}' id='{}' size={}",
                    doc.getTenantId(), doc.getProjectId(), doc.getPath(), id, newBytes.length);
            return doc;
        }

        if (doc.getLineageId() == null || doc.getLineageId().isBlank()) {
            doc.setLineageId(java.util.UUID.randomUUID().toString());
        }
        if (mimeChanged) {
            doc.setMimeType(newMimeType);
        }

        boolean archived = false;
        if (contentChanged && shouldArchiveOnSave(doc)) {
            try {
                archiveService.archiveCurrent(doc);
                doc.setLastArchivedAt(Instant.now());
                archived = true;
            } catch (RuntimeException e) {
                log.warn("Failed to archive document id='{}' before content replace — "
                                + "proceeding without version snapshot", id, e);
            }
        }
        String oldStorageId = doc.getStorageId();

        ContentWriteResult write = streamingStoreContent(
                doc.getTenantId(), doc.getPath(), new ByteArrayInputStream(newBytes));
        doc.setStorageId(write.storageId());
        doc.setCompressed(write.compressed());
        doc.setSize(write.originalSize());
        if (oldStorageId != null
                && !oldStorageId.equals(write.storageId())
                && !archived) {
            deleteStorageBlobQuietly(oldStorageId, id);
        }

        if (contentChanged) {
            doc.setSummaryDirty(true);
            doc.setRagDirty(isRagEligible(doc));
        }
        applyHeader(doc);

        DocumentDocument saved = repository.save(doc);
        log.info("Replaced content tenantId='{}' projectId='{}' path='{}' id='{}' size={} compressed={}",
                saved.getTenantId(), saved.getProjectId(), saved.getPath(),
                saved.getId(), saved.getSize(), saved.isCompressed());
        return publishUpserted(saved, contentChanged, identity);
    }

    /**
     * Cheap byte-equality check used to short-circuit no-op writes in
     * {@link #replaceContent}. Falls back to "changed" (returns {@code true})
     * on any read failure — the safe direction is to proceed with the
     * write rather than silently drop a real edit.
     */
    private boolean isContentDifferent(DocumentDocument doc, byte[] newBytes) {
        if (doc.getSize() != newBytes.length) return true;
        try (InputStream existing = loadContent(doc);
                ByteArrayOutputStream sink = new ByteArrayOutputStream(newBytes.length)) {
            existing.transferTo(sink);
            return !java.util.Arrays.equals(sink.toByteArray(), newBytes);
        } catch (IOException | RuntimeException e) {
            log.debug("isContentDifferent: read failed for id='{}', treating as changed: {}",
                    doc.getId(), e.toString());
            return true;
        }
    }

    /**
     * Replace the binary content of an existing document. Always
     * writes through to the storage layer (no inline-text branch
     * — binary by design). Used by the office-editor callback path
     * to persist DOCX/XLSX bytes coming back from ONLYOFFICE /
     * Collabora.
     *
     * @param id          document id
     * @param newMimeType optional new mime — when {@code null} the
     *                    document's existing mime is preserved
     * @param bytes       new content
     * @param updatedBy   optional audit field for the save
     * @return the updated document
     * @throws IllegalArgumentException if the document is unknown
     */
    public DocumentDocument replaceBinaryContent(
            String id,
            @Nullable String newMimeType,
            byte[] bytes,
            @Nullable String updatedBy) {
        DocumentDocument doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown document id='" + id + "'"));
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "bytes must not be null for binary replace");
        }
        if (doc.getLineageId() == null) {
            doc.setLineageId(java.util.UUID.randomUUID().toString());
        }
        String oldStorageId = doc.getStorageId();
        ContentWriteResult write;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            write = streamingStoreContent(
                    doc.getTenantId(), doc.getPath(), in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to stream replaced binary content for id='"
                            + id + "'", e);
        }
        if (newMimeType != null && !newMimeType.isBlank()) {
            doc.setMimeType(newMimeType);
        }
        doc.setStorageId(write.storageId());
        doc.setCompressed(write.compressed());
        doc.setSize(write.originalSize());
        doc.setRagDirty(isRagEligible(doc));
        // updatedBy is unused for now — DocumentDocument has no
        // last-editor field. Reserved in the signature so the
        // office-callback path can record audit info once that
        // field lands.
        DocumentDocument saved = repository.save(doc);
        if (oldStorageId != null && !oldStorageId.equals(write.storageId())) {
            deleteStorageBlobQuietly(oldStorageId, id);
        }
        log.info("Replaced binary content tenantId='{}' projectId='{}' "
                        + "path='{}' id='{}' bytes={} compressed={}",
                saved.getTenantId(), saved.getProjectId(),
                saved.getPath(), saved.getId(), saved.getSize(), saved.isCompressed());
        return publishUpserted(saved);
    }

    /**
     * Create-or-replace a binary document with attached metadata.
     * Used by the image-generation pipeline (Fenchurch) and any other
     * caller that needs to write bytes + a curated {@code headers} map
     * in one step.
     *
     * <p>If the document at {@code path} exists, its content is replaced
     * via {@link #replaceBinaryContent}; otherwise a fresh document is
     * created via {@link #create}. In both branches the supplied
     * {@code headers} replace the document's current header map outright
     * (binary documents have no in-body frontmatter for
     * {@link #applyHeader} to rebuild from, so the explicit caller-set
     * values are the only source of truth).
     *
     * <p>{@code title} / {@code tags} follow "null = unchanged" semantics
     * on the replace path; on the create path they're forwarded to
     * {@link #create} verbatim (where {@code null} means
     * "leave empty / no tags").
     *
     * @param tenantId   owning tenant
     * @param projectId  owning project
     * @param path       virtual path inside the project
     * @param bytes      content (required, non-null)
     * @param mimeType   content mime type (required, non-blank)
     * @param title      human-readable title; {@code null} leaves it
     *                   untouched on replace
     * @param tags       tag list; {@code null} leaves it untouched on
     *                   replace
     * @param headers    metadata map to attach; {@code null} leaves the
     *                   current headers untouched, empty map clears
     *                   them
     * @param createdBy  audit field forwarded to {@link #create} /
     *                   {@link #replaceBinaryContent}
     */
    public DocumentDocument createOrReplaceBinary(
            String tenantId,
            String projectId,
            String path,
            byte[] bytes,
            String mimeType,
            @Nullable String title,
            @Nullable List<String> tags,
            @Nullable Map<String, String> headers,
            @Nullable String createdBy) {
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "bytes must not be null for binary write");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException(
                    "mimeType must not be blank for binary write");
        }
        Optional<DocumentDocument> existing = findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            DocumentDocument doc = replaceBinaryContent(
                    existing.get().getId(), mimeType, bytes, createdBy);
            boolean changed = false;
            if (title != null) {
                doc.setTitle(title);
                changed = true;
            }
            if (tags != null) {
                doc.setTags(new ArrayList<>(tags));
                changed = true;
            }
            if (headers != null) {
                doc.setHeaders(new LinkedHashMap<>(headers));
                changed = true;
            }
            return changed ? repository.save(doc) : doc;
        }
        DocumentDocument doc = create(
                tenantId, projectId, path,
                title, tags, mimeType,
                new ByteArrayInputStream(bytes),
                createdBy);
        if (headers != null) {
            doc.setHeaders(new LinkedHashMap<>(headers));
            doc = repository.save(doc);
        }
        return doc;
    }

    /**
     * Opens a streaming read over the document's content. Caller closes.
     * Returns an empty stream for documents that have neither inline text nor
     * a storage blob (shouldn't happen, but defensive).
     */
    // ──────────────────── Cascade lookup ────────────────────

    /**
     * Resolve {@code path} along the cascade
     * project → {@code _vance} → classpath ({@code vance-defaults/<path>}).
     * Returns the first match — innermost wins. Storage-backed and
     * inline documents are both materialized to a UTF-8 string, so the
     * caller never has to differentiate.
     *
     * <p>Calling with {@code projectId == "_tenant"} just collapses the
     * first two layers — the project lookup and the {@code _vance}
     * lookup are the same row and we don't read it twice.
     *
     * @return the resolved document, or {@link Optional#empty()} when
     *         no layer carries the path
     */
    public Optional<LookupResult> lookupCascade(
            String tenantId, String projectId, String path) {
        String norm = normalizePath(path);

        // 1. Project (if not the _vance project itself).
        if (!HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            Optional<LookupResult> hit = tryProjectLookup(
                    tenantId, projectId, norm, LookupResult.Source.PROJECT);
            if (hit.isPresent()) return hit;
        }

        // 2. _vance project.
        Optional<LookupResult> vance = tryProjectLookup(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, norm,
                LookupResult.Source.VANCE);
        if (vance.isPresent()) return vance;

        // 3. Classpath fallback.
        return loadResource(norm).map(content ->
                new LookupResult(norm, content, LookupResult.Source.RESOURCE, null));
    }

    /**
     * List all documents one level under {@code pathPrefix}, merged
     * across the cascade. Inner sources overwrite outer ones <b>per
     * normalized path</b>: a {@code documents/how_to_tools.md} found in
     * the user's project replaces the same path coming from
     * {@code _vance} or from the classpath default — exactly one entry
     * survives in the returned map regardless of how many layers carry it.
     *
     * <p>One level means: only paths that match {@code <prefix>/<name>}
     * with no further slashes. {@code documents/sub/foo.md} is excluded
     * when {@code pathPrefix} is {@code documents/}.
     *
     * <p>The map iteration order is the cascade application order
     * (resources first, then {@code _vance}, then project) — useful for
     * diagnostics, not for semantics. Semantically the map carries
     * "the value the innermost layer set last".
     *
     * @param pathPrefix folder path. Trailing slash is optional;
     *                   passing {@code ""} or {@code "/"} means top-level
     *                   (matches paths without any slash).
     */
    public Map<String, LookupResult> listByPrefixCascade(
            String tenantId, String projectId, String pathPrefix) {
        String prefix = normalizePrefix(pathPrefix);
        Map<String, LookupResult> merged = new LinkedHashMap<>();

        // Outer-to-inner: each subsequent put() overrides the previous
        // entry for the same path. This guarantees no duplicates and
        // honours "innermost wins" without tracking seen-keys manually.
        applyResourceLayer(merged, prefix);
        applyProjectLayer(merged,
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, prefix,
                LookupResult.Source.VANCE);
        if (!HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            applyProjectLayer(merged,
                    tenantId, projectId, prefix,
                    LookupResult.Source.PROJECT);
        }
        return merged;
    }

    // ──────────────────── Cascade helpers ────────────────────

    private Optional<LookupResult> tryProjectLookup(
            String tenantId, String projectId, String normalizedPath,
            LookupResult.Source source) {
        return repository
                .findByTenantIdAndProjectIdAndPath(tenantId, projectId, normalizedPath)
                .filter(doc -> doc.getStatus() == DocumentStatus.ACTIVE)
                .map(doc -> new LookupResult(
                        doc.getPath(), readAsString(doc), source, doc));
    }

    private void applyProjectLayer(
            Map<String, LookupResult> acc,
            String tenantId, String projectId, String prefix,
            LookupResult.Source source) {
        for (DocumentDocument doc : repository.findByTenantIdAndProjectIdAndStatus(
                tenantId, projectId, DocumentStatus.ACTIVE)) {
            String path = doc.getPath();
            if (path == null || !matchesOneLevel(path, prefix)) continue;
            acc.put(path, new LookupResult(path, readAsString(doc), source, doc));
        }
    }

    private void applyResourceLayer(Map<String, LookupResult> acc, String prefix) {
        // Pattern: classpath:vance-defaults/<prefix>* — '*' does not match
        // slashes in Spring's AntPathMatcher, so this naturally enforces
        // the one-level rule for resources too.
        String pattern = "classpath*:" + RESOURCE_PREFIX + prefix + "*";
        Resource[] resources;
        try {
            resources = resourcePatternResolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("Failed to scan classpath '{}': {}", pattern, e.toString());
            return;
        }
        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) continue;
            String filename = resource.getFilename();
            if (filename == null || filename.isEmpty()) continue;
            String path = prefix + filename;
            try (InputStream in = resource.getInputStream()) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                acc.put(path, new LookupResult(
                        path, content, LookupResult.Source.RESOURCE, null));
            } catch (IOException e) {
                log.warn("Failed to read classpath resource '{}': {}",
                        resource, e.toString());
            }
        }
    }

    private Optional<String> loadResource(String normalizedPath) {
        String resourcePath = RESOURCE_PREFIX + normalizedPath;
        try {
            Resource resource = resourcePatternResolver.getResource(
                    "classpath:" + resourcePath);
            if (!resource.exists() || !resource.isReadable()) {
                return Optional.empty();
            }
            try (InputStream in = resource.getInputStream()) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            log.warn("Failed to read classpath resource '{}': {}", resourcePath, e.toString());
            return Optional.empty();
        }
    }

    private String readAsString(DocumentDocument doc) {
        try (InputStream in = loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read document id='{}' path='{}': {}",
                    doc.getId(), doc.getPath(), e.toString());
            return "";
        }
    }

    /**
     * Normalize a folder prefix to either {@code ""} (top level) or
     * {@code "<folder>/"}. Defends against null, leading slash, missing
     * trailing slash.
     */
    private static String normalizePrefix(@Nullable String pathPrefix) {
        if (pathPrefix == null) return "";
        String trimmed = pathPrefix.trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        if (trimmed.isEmpty()) return "";
        if (!trimmed.endsWith("/")) trimmed = trimmed + "/";
        return trimmed;
    }

    /**
     * {@code true} when {@code path} sits exactly one level under
     * {@code prefix} — i.e. starts with the prefix and the remainder
     * contains no further slash. Both arguments are expected normalized.
     */
    private static boolean matchesOneLevel(String path, String prefix) {
        if (!path.startsWith(prefix)) return false;
        String rest = path.substring(prefix.length());
        if (rest.isEmpty()) return false;
        return rest.indexOf('/') < 0;
    }

    public InputStream loadContent(DocumentDocument doc) {
        String sid = doc.getStorageId();
        if (sid == null) {
            return InputStream.nullInputStream();
        }
        InputStream stream = storageService.load(sid);
        if (stream == null) {
            log.warn("StorageService returned null for document id='{}' storageId='{}'",
                    doc.getId(), sid);
            return InputStream.nullInputStream();
        }
        if (doc.isCompressed()) {
            try {
                return new GZIPInputStream(stream);
            } catch (IOException e) {
                log.warn("Failed to open gzip stream for document id='{}' storageId='{}': {}",
                        doc.getId(), sid, e.toString());
                try { stream.close(); } catch (IOException ignored) { /* best effort */ }
                return InputStream.nullInputStream();
            }
        }
        return stream;
    }

    /**
     * Apply a partial update. Each parameter is independently optional —
     * pass {@code null} to leave that field untouched.
     *
     * <p>{@code newInlineText} is only honoured for documents that already
     * live inline (i.e. {@link DocumentDocument#getInlineText()} is non-null)
     * and only when the new text fits within {@code vance.document.inline-threshold}.
     * Storage-backed documents are read-only in v1 with respect to content.
     * {@code newPath} works for any document (inline or storage-backed) —
     * it's metadata, not content.
     *
     * @return the updated document
     * @throws IllegalArgumentException if the document is unknown or the update
     *         is rejected (storage-backed content, oversize inline text,
     *         blank/invalid path)
     * @throws DocumentAlreadyExistsException if {@code newPath} clashes with
     *         a sibling document in the same project
     */
    public DocumentDocument update(
            String id,
            @Nullable String newTitle,
            @Nullable List<String> newTags,
            @Nullable String newInlineText,
            @Nullable String newPath) {
        return update(id, newTitle, newTags, newInlineText, newPath, null, null);
    }

    /**
     * Overload that also accepts the two auto-summary flags
     * ({@code autoSummary}, {@code summaryDirty}) — pass {@code null} to
     * leave either untouched. Setting {@code summaryDirty = true} forces
     * the next scheduler tick to re-summarise the document even without
     * a content change.
     */
    public DocumentDocument update(
            String id,
            @Nullable String newTitle,
            @Nullable List<String> newTags,
            @Nullable String newInlineText,
            @Nullable String newPath,
            @Nullable Boolean newAutoSummary,
            @Nullable Boolean newSummaryDirty) {
        return update(id, newTitle, newTags, newInlineText, newPath,
                newAutoSummary, newSummaryDirty, null);
    }

    /**
     * Overload that also accepts the {@code ragEnabled} tri-state override.
     * {@code null} means "leave untouched" (or "auto" if never set);
     * {@code true} forces RAG inclusion, {@code false} excludes the document
     * from the project RAG.
     */
    public DocumentDocument update(
            String id,
            @Nullable String newTitle,
            @Nullable List<String> newTags,
            @Nullable String newInlineText,
            @Nullable String newPath,
            @Nullable Boolean newAutoSummary,
            @Nullable Boolean newSummaryDirty,
            @Nullable Boolean newRagEnabled) {
        return update(id, newTitle, newTags, newInlineText, newPath,
                newAutoSummary, newSummaryDirty, newRagEnabled, null);
    }

    /**
     * Overload that also accepts a {@code newMimeType} override. Use
     * when the original guess from the upload was wrong (e.g. a doc
     * that came in as {@code text/plain} but is actually Markdown).
     * {@code null} leaves the current MIME type untouched. The new
     * value is applied <b>before</b> the inline-text branch runs so the
     * inline-vs-storage threshold uses the new mime type's textual
     * status for the decision.
     */
    public DocumentDocument update(
            String id,
            @Nullable String newTitle,
            @Nullable List<String> newTags,
            @Nullable String newInlineText,
            @Nullable String newPath,
            @Nullable Boolean newAutoSummary,
            @Nullable Boolean newSummaryDirty,
            @Nullable Boolean newRagEnabled,
            @Nullable String newMimeType) {
        return update(id, newTitle, newTags, newInlineText, newPath,
                newAutoSummary, newSummaryDirty, newRagEnabled, newMimeType,
                TOOL_IDENTITY);
    }

    /**
     * Single-editorId-overload — kept for callers that have only the
     * editorId on hand. Translates to a {@link WriterIdentity} with
     * null user fields.
     */
    public DocumentDocument update(
            String id,
            @Nullable String newTitle,
            @Nullable List<String> newTags,
            @Nullable String newInlineText,
            @Nullable String newPath,
            @Nullable Boolean newAutoSummary,
            @Nullable Boolean newSummaryDirty,
            @Nullable Boolean newRagEnabled,
            @Nullable String newMimeType,
            @Nullable String editorId) {
        return update(id, newTitle, newTags, newInlineText, newPath,
                newAutoSummary, newSummaryDirty, newRagEnabled, newMimeType,
                WriterIdentity.of(editorId, null, null));
    }

    /**
     * Same as the {@code String editorId} overload but with full
     * {@link WriterIdentity} — used by REST controllers that have a
     * JWT-bound user along with the X-Editor-Id header.
     */
    public DocumentDocument update(
            String id,
            @Nullable String newTitle,
            @Nullable List<String> newTags,
            @Nullable String newInlineText,
            @Nullable String newPath,
            @Nullable Boolean newAutoSummary,
            @Nullable Boolean newSummaryDirty,
            @Nullable Boolean newRagEnabled,
            @Nullable String newMimeType,
            WriterIdentity identity) {

        DocumentDocument doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document id='" + id + "'"));

        // Lazy backfill for documents created before lineage-tracking
        // landed — a blank lineageId would prevent archiving forever
        // otherwise. Cheap: one UUID assignment, in-place; the save
        // call further down persists it.
        if (doc.getLineageId() == null || doc.getLineageId().isBlank()) {
            doc.setLineageId(java.util.UUID.randomUUID().toString());
        }

        if (newTitle != null) doc.setTitle(newTitle);
        if (newTags != null) doc.setTags(new ArrayList<>(newTags));
        if (newAutoSummary != null) doc.setAutoSummary(newAutoSummary);
        if (newSummaryDirty != null) doc.setSummaryDirty(newSummaryDirty);
        if (newRagEnabled != null) doc.setRagEnabled(newRagEnabled);
        // Apply the mime-type change first so the inline-vs-storage
        // decision below uses the new textual flag (e.g. switching
        // text/plain → application/octet-stream pushes a previously
        // inline doc to storage on its next inlineText update).
        if (newMimeType != null && !newMimeType.isBlank()) {
            doc.setMimeType(newMimeType);
        }

        // Tracked across the branches below and consulted at the bottom
        // when deciding whether to emit a DocumentLiveChangedEvent. Live
        // subscribers only care about body / path movement — metadata
        // edits (title/tags/mime) do not warrant a "document changed"
        // banner in the editor.
        boolean contentChanged = false;
        boolean pathChanged = false;

        if (newInlineText != null) {
            byte[] bytes = newInlineText.getBytes(StandardCharsets.UTF_8);
            String currentContent = readAsString(doc);
            contentChanged = !newInlineText.equals(currentContent);

            // Archive the *current* version before overwriting — but only
            // when (a) the content is actually changing, (b) archiving is
            // enabled in the cascade, and (c) the previous archive (if any)
            // is older than the min-version-interval. archiveCurrent()
            // moves the storage pointer; afterwards doc.storageId is null
            // and the old blob lives on under the archive entry — so the
            // storage-cleanup branches below must NOT try to delete it
            // again.
            boolean archived = false;
            if (contentChanged && shouldArchiveOnSave(doc)) {
                try {
                    archiveService.archiveCurrent(doc);
                    doc.setLastArchivedAt(Instant.now());
                    archived = true;
                } catch (RuntimeException e) {
                    // Archive failure must not lose the user's save —
                    // log and proceed with the regular update path
                    // (the old version is then lost, which is the same
                    // behaviour we had before versioning landed).
                    log.warn("Failed to archive document id='{}' before update — "
                                    + "proceeding without version snapshot",
                            id, e);
                }
            }
            String oldStorageId = doc.getStorageId();

            // Always write new storage blob — no inline branch, no in-place
            // storage edit. "Write new before delete old" means a crash
            // mid-update leaves both blobs; orphan reclaim handles that.
            ContentWriteResult write;
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                write = streamingStoreContent(
                        doc.getTenantId(), doc.getPath(), in);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to stream updated document content to "
                                + "storage for id='" + id + "'", e);
            }
                doc.setStorageId(write.storageId());
            doc.setCompressed(write.compressed());
            doc.setSize(write.originalSize());
            if (oldStorageId != null
                    && !oldStorageId.equals(write.storageId())
                    && !archived) {
                deleteStorageBlobQuietly(oldStorageId, id);
            }

            if (contentChanged) {
                // Mark dirty for the auto-summary scheduler. Header /
                // tags / title edits without content change do not
                // touch this flag — summary follows the body, not the
                // metadata.
                doc.setSummaryDirty(true);
            }
            applyHeader(doc);
        }

        if (newPath != null) {
            String normalized = normalizePath(newPath);
            if (!normalized.equals(doc.getPath())) {
                if (repository.existsByTenantIdAndProjectIdAndPath(
                        doc.getTenantId(), doc.getProjectId(), normalized)) {
                    throw new DocumentAlreadyExistsException(
                            "Document '" + normalized + "' already exists in "
                                    + doc.getTenantId() + "/" + doc.getProjectId());
                }
                doc.setPath(normalized);
                doc.setName(extractName(normalized));
                pathChanged = true;
            }
        }

        // Mark dirty for the project-RAG indexer whenever the body, the path,
        // or the ragEnabled flag changes — the indexer applies the §4.2 filter
        // and decides include vs. purge per tick. Re-marking an already-dirty
        // doc is a no-op.
        if (newInlineText != null || newPath != null || newRagEnabled != null) {
            doc.setRagDirty(true);
        }

        DocumentDocument saved = repository.save(doc);
        log.info("Updated document tenantId='{}' projectId='{}' id='{}' fields={}",
                saved.getTenantId(), saved.getProjectId(), saved.getId(),
                describeChanges(newTitle, newTags, newInlineText, newPath, newMimeType));
        return publishUpserted(saved, contentChanged || pathChanged, identity);
    }

    /**
     * Decision: should the next save archive the current version first?
     * Cascade order, first hit wins:
     *
     * <ol>
     *   <li>operator kill-switch {@code vance.documents.archive.enabled}
     *       (application.yml) — when {@code false}, never archive;</li>
     *   <li>per-project / tenant setting {@value #SETTING_ARCHIVE_ENABLED}
     *       (default {@code true});</li>
     *   <li>min-version-interval: archive only when
     *       {@code lastArchivedAt} is either {@code null} (first save
     *       after create) is treated as "do not archive" — first version
     *       only materialises on the second save after the interval
     *       elapsed, or when the previous archive is older than the
     *       project's configured min-interval.</li>
     * </ol>
     *
     * <p>Visibility-relaxed (package-private) so unit tests can stub
     * the cascade reads cheaply.
     */
    boolean shouldArchiveOnSave(DocumentDocument doc) {
        if (!archiveEnabledDefault) return false;
        boolean projectEnabled = settingService.getBooleanValueCascade(
                doc.getTenantId(), doc.getProjectId(), /*thinkProcessId*/ null,
                SETTING_ARCHIVE_ENABLED, /*default*/ true);
        if (!projectEnabled) return false;
        Instant last = doc.getLastArchivedAt();
        if (last == null) {
            // First save after create — no version yet. The implicit
            // "create" is the version-zero, so we only archive on the
            // *next* save once enough time has passed since create.
            // Treat the document's createdAt as the reference timestamp.
            last = doc.getCreatedAt();
        }
        if (last == null) return true; // no reference — be safe, archive.
        long minSeconds = resolveMinIntervalSeconds(
                doc.getTenantId(), doc.getProjectId());
        return Instant.now().isAfter(last.plusSeconds(minSeconds));
    }

    private long resolveMinIntervalSeconds(String tenantId, String projectId) {
        String raw = settingService.getStringValueCascade(
                tenantId, projectId, /*thinkProcessId*/ null,
                SETTING_ARCHIVE_MIN_INTERVAL_SECONDS);
        if (raw == null || raw.isBlank()) {
            return archiveMinIntervalSecondsDefault;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < 0) return archiveMinIntervalSecondsDefault;
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("Invalid {} setting for tenantId='{}' projectId='{}': '{}' — falling back to default {}",
                    SETTING_ARCHIVE_MIN_INTERVAL_SECONDS, tenantId, projectId,
                    raw, archiveMinIntervalSecondsDefault);
            return archiveMinIntervalSecondsDefault;
        }
    }

    // ──────────────────── Archive read API ────────────────────

    /**
     * Number of archive entries for {@code doc}. UI badge.
     */
    public long countArchives(DocumentDocument doc) {
        return archiveService.countForLineage(
                doc.getTenantId(), doc.getProjectId(), doc.getLineageId());
    }

    /**
     * All archive entries for {@code doc}'s lineage, newest first.
     */
    public List<DocumentArchiveDocument> listArchives(DocumentDocument doc) {
        return archiveService.listForLineage(
                doc.getTenantId(), doc.getProjectId(), doc.getLineageId());
    }

    public Optional<DocumentArchiveDocument> findArchive(String archiveId) {
        return archiveService.findById(archiveId);
    }

    /** Read the archive's body as a UTF-8 string. */
    public String readArchiveContent(DocumentArchiveDocument archive) {
        return archiveService.readContentAsString(archive);
    }

    /** Streaming read over the archive's body — caller closes. */
    public InputStream loadArchiveContent(DocumentArchiveDocument archive) {
        return archiveService.loadContent(archive);
    }

    /**
     * Delete a single archive entry. Lineage check is the caller's
     * responsibility (REST layer matches {@code archiveId} against the
     * lineage of the addressed document).
     */
    public void deleteArchive(String archiveId) {
        archiveService.deleteArchive(archiveId);
    }

    /**
     * Restore {@code archive} into the live document {@code liveDocId}.
     * The current live content is archived first (so the restore is
     * itself a versioned save) and then overwritten with a fresh copy
     * of the archive's body. The archive entry is left untouched —
     * appears in the version list as before.
     *
     * <p>Storage-backed archives are restored via
     * {@link DocumentArchiveService.RestorePayload} which carries a
     * freshly duplicated blob id; no blob sharing between archive and
     * live document at any point.
     *
     * @throws IllegalArgumentException if the archive does not belong
     *         to the live document's lineage.
     */
    public DocumentDocument restoreArchive(String liveDocId, String archiveId) {
        DocumentDocument live = repository.findById(liveDocId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown document id='" + liveDocId + "'"));
        DocumentArchiveDocument archive = archiveService.findById(archiveId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown archive id='" + archiveId + "'"));
        if (!archive.getLineageId().equals(live.getLineageId())) {
            throw new IllegalArgumentException(
                    "Archive id='" + archiveId + "' does not belong to "
                            + "document id='" + liveDocId
                            + "' (lineage mismatch)");
        }

        // 1. Archive the current live content (unconditional — restoring
        //    is itself a meaningful version event, regardless of the
        //    min-version-interval setting; otherwise an undo immediately
        //    after a save would silently lose data).
        try {
            archiveService.archiveCurrent(live);
            live.setLastArchivedAt(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Failed to archive document id='{}' before restore — "
                            + "proceeding without snapshot of current version", liveDocId, e);
            // Continue: an explicit restore takes priority over preserving
            // the current version. The user asked for the older version
            // back.
        }
        String oldStorageId = live.getStorageId();

        // 2. Apply restored content. archiveService.restore() duplicated the
        //    archive's blob; we point the live row at the freshly minted
        //    storageId. The old storage pointer (if any) was moved to the
        //    archive entry in step 1 — but defensive: if archive failed and
        //    oldStorageId is still live, we'd still need to clean it up
        //    afterwards.
        DocumentArchiveService.RestorePayload payload = archiveService.restore(archive);
        live.setStorageId(payload.storageId());
        live.setCompressed(payload.compressed());
        live.setSize(payload.size());
        if (payload.mimeType() != null) live.setMimeType(payload.mimeType());
        if (payload.title() != null) live.setTitle(payload.title());
        live.setTags(new ArrayList<>(payload.tags()));
        live.setSummaryDirty(true);
        live.setRagDirty(true);
        applyHeader(live);

        DocumentDocument saved = repository.save(live);

        // 3. If the archive step failed and the live's old storage pointer
        //    was kept around, it's orphaned now — delete it. (When the
        //    archive succeeded, storageId was already cleared before we
        //    got here, so this is a no-op then.)
        if (oldStorageId != null && !oldStorageId.equals(saved.getStorageId())) {
            deleteStorageBlobQuietly(oldStorageId, liveDocId);
        }
        log.info("Restored document id='{}' from archive id='{}' lineageId='{}' archivedAt='{}'",
                liveDocId, archiveId, archive.getLineageId(), archive.getArchivedAt());
        return saved;
    }

    /** Best-effort blob delete invoked after an
     *  inline→storage or storage→storage update has rewritten
     *  the doc's storageId. Failure to delete leaves an orphan
     *  blob, which orphan-reclaim handles separately. */
    private void deleteStorageBlobQuietly(String storageId, String docId) {
        try {
            storageService.delete(storageId);
        } catch (Exception e) {
            log.warn("Failed to delete old storage blob during update — "
                            + "leaving orphan; docId='{}' storageId='{}'",
                    docId, storageId, e);
        }
    }

    private static String describeChanges(
            @Nullable String title,
            @Nullable List<String> tags,
            @Nullable String inlineText,
            @Nullable String path,
            @Nullable String mimeType) {
        StringBuilder sb = new StringBuilder("[");
        if (title != null) sb.append("title");
        if (tags != null) { if (sb.length() > 1) sb.append(','); sb.append("tags"); }
        if (inlineText != null) { if (sb.length() > 1) sb.append(','); sb.append("inlineText"); }
        if (path != null) { if (sb.length() > 1) sb.append(','); sb.append("path"); }
        if (mimeType != null) { if (sb.length() > 1) sb.append(','); sb.append("mimeType"); }
        return sb.append(']').toString();
    }

    // ──────────────────── Auto-summary ────────────────────

    /**
     * Claim up to {@code batchSize} dirty documents in
     * {@code (tenantId, projectId)} for the auto-summary scheduler.
     * Each document is acquired via an atomic {@code findAndModify}
     * so concurrent pods on the same project (user-project roaming)
     * see disjoint claims.
     *
     * <p>Before the claim loop, stale claims older than
     * {@code claimTtl} are released — this is the self-healing path
     * for pod crashes between {@code claim} and {@code writeSummary}.
     *
     * <p>Match query: {@code summaryDirty=true ∧ autoSummary=true ∧
     * claimedBy=null}. Documents with the dirty flag set but
     * {@code autoSummary=false} (user-disabled or non-text mime) are
     * skipped.
     *
     * @return the documents now claimed by {@code podId}. May be empty
     *         when no dirty documents are available.
     */
    public List<DocumentDocument> claimForSummary(
            String tenantId, String projectId,
            String podId, int batchSize, Duration claimTtl) {

        // 1. Stale-claim recovery: free anything that's been claimed
        //    for longer than the TTL. Bounded by the index, single
        //    multi-update — cheap.
        Query stale = new Query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId)
                .and("claimedBy").ne(null)
                .and("claimedAt").lt(Instant.now().minus(claimTtl)));
        mongoTemplate.updateMulti(stale,
                new Update().unset("claimedBy").unset("claimedAt"),
                DocumentDocument.class);

        // 2. Claim loop: per-document findAndModify until the batch is
        //    full or no more candidates are available.
        List<DocumentDocument> claimed = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            Query q = new Query(Criteria.where("tenantId").is(tenantId)
                    .and("projectId").is(projectId)
                    .and("summaryDirty").is(true)
                    .and("autoSummary").is(true)
                    .and("claimedBy").is(null));
            Update u = new Update()
                    .set("claimedBy", podId)
                    .set("claimedAt", Instant.now());
            DocumentDocument doc = mongoTemplate.findAndModify(
                    q, u, FindAndModifyOptions.options().returnNew(true),
                    DocumentDocument.class);
            if (doc == null) break;
            claimed.add(doc);
        }
        return claimed;
    }

    /**
     * Persist the summary + tags produced by the auto-summary driver
     * and release the claim. Goes through {@link MongoTemplate}
     * directly (not {@link DocumentRepository#save}) on purpose —
     * a full-document save would re-fire any {@code @LastModifiedDate}
     * tracking and re-run {@code applyHeader}, and there's no scenario
     * where overwriting tags + summary should be re-marking the
     * document dirty.
     */
    public void writeSummary(String id, String summary, List<String> tags) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("summary", summary)
                        .set("tags", tags)
                        .set("summarizedAt", Instant.now())
                        .set("summaryDirty", false)
                        .unset("claimedBy")
                        .unset("claimedAt"),
                DocumentDocument.class);
    }

    /**
     * Set the summary directly without touching tags or claim state.
     * Used by callers that have a summary in hand (LLM ingestion via
     * {@code doc_import_url(..., summary=...)}, user edit through
     * {@code DocumentController#update}, or the future
     * {@code doc_set_summary} tool) and want to skip the
     * claim/release dance the scheduler does.
     *
     * <p>Clears {@code summaryDirty} so the scheduler doesn't pick the
     * document up again immediately, and stamps {@code summarizedAt}
     * for the audit trail. An empty/blank summary clears the field.
     */
    public void setSummary(String id, @Nullable String summary) {
        String normalised = (summary != null && !summary.isBlank())
                ? summary.trim() : null;
        Update update = new Update()
                .set("summarizedAt", Instant.now())
                .set("summaryDirty", false);
        if (normalised == null) {
            update.unset("summary");
        } else {
            update.set("summary", normalised);
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                update,
                DocumentDocument.class);
    }

    /**
     * Release a claim without touching {@code summaryDirty}. The
     * scheduler calls this when the LLM run failed — the document
     * stays dirty so the next tick picks it up again.
     */
    public void releaseClaim(String id) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().unset("claimedBy").unset("claimedAt"),
                DocumentDocument.class);
    }

    // ──────────────────── Project RAG indexing ────────────────────

    /**
     * Returns {@code true} when the document should land in the
     * project-default RAG ({@code _documents}) under the rule:
     * <ul>
     *   <li>{@code ragEnabled == true} → always include</li>
     *   <li>{@code ragEnabled == false} → always exclude</li>
     *   <li>{@code ragEnabled == null} → default = path under
     *       {@link #DOCUMENTS_FOLDER_PREFIX} AND textual mime-type</li>
     * </ul>
     * See {@code planning/project-rag.md} §4.2.
     */
    public boolean isRagEligible(DocumentDocument doc) {
        Boolean override = doc.getRagEnabled();
        if (override != null) return override;
        if (doc.getPath() == null) return false;
        if (!doc.getPath().startsWith(DOCUMENTS_FOLDER_PREFIX)) return false;
        return isTextual(doc.getMimeType());
    }

    /**
     * Claim up to {@code batchSize} dirty documents in
     * {@code (tenantId, projectId)} for the project-RAG indexer.
     * Atomic {@code findAndModify} per document so concurrent pods on
     * the same project (user-project roaming) see disjoint claims.
     *
     * <p>Match query: {@code ragDirty=true ∧ ragClaimedBy=null}.
     * Filter eligibility is re-checked by the indexer per document
     * (race-safe against ragEnabled toggle between dirty-set and claim).
     *
     * <p>Stale claims older than {@code claimTtl} are released first
     * — self-healing for pod crashes between claim and write.
     */
    public List<DocumentDocument> claimForRagIndex(
            String tenantId, String projectId,
            String podId, int batchSize, Duration claimTtl) {

        Query stale = new Query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId)
                .and("ragClaimedBy").ne(null)
                .and("ragClaimedAt").lt(Instant.now().minus(claimTtl)));
        mongoTemplate.updateMulti(stale,
                new Update().unset("ragClaimedBy").unset("ragClaimedAt"),
                DocumentDocument.class);

        List<DocumentDocument> claimed = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            Query q = new Query(Criteria.where("tenantId").is(tenantId)
                    .and("projectId").is(projectId)
                    .and("ragDirty").is(true)
                    .and("ragClaimedBy").is(null));
            Update u = new Update()
                    .set("ragClaimedBy", podId)
                    .set("ragClaimedAt", Instant.now());
            DocumentDocument doc = mongoTemplate.findAndModify(
                    q, u, FindAndModifyOptions.options().returnNew(true),
                    DocumentDocument.class);
            if (doc == null) break;
            claimed.add(doc);
        }
        return claimed;
    }

    /**
     * Clear {@code ragDirty} and release the claim — called by the
     * indexer after chunks were written successfully (or after a
     * filter-purge that legitimately empties the chunk set).
     */
    public void markRagClean(String id) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("ragDirty", false)
                        .unset("ragClaimedBy")
                        .unset("ragClaimedAt"),
                DocumentDocument.class);
    }

    /**
     * Release a claim without clearing {@code ragDirty}. Indexer calls
     * this on transient failure — claim TTL would also free it, this
     * is the eager path.
     */
    public void releaseRagClaim(String id) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().unset("ragClaimedBy").unset("ragClaimedAt"),
                DocumentDocument.class);
    }

    /**
     * Atomically mark every document in {@code (tenantId, projectId)}
     * for re-indexing. Used by {@code ProjectRagService.reindex(...)}.
     * Released claims are not cleared — they expire via TTL.
     */
    public long markAllForReindex(String tenantId, String projectId) {
        Query q = new Query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId)
                .and("status").is(DocumentStatus.ACTIVE));
        Update u = new Update().set("ragDirty", true);
        return mongoTemplate.updateMulti(q, u, DocumentDocument.class)
                .getModifiedCount();
    }

    /**
     * Mark the document dirty for re-indexing — used by the auto-summary
     * driver after it writes a new summary so the indexer picks up the
     * summary-chunk on the next tick.
     */
    public void markRagDirty(String id) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().set("ragDirty", true),
                DocumentDocument.class);
    }

    /**
     * Set the tri-state {@code ragEnabled} override and re-flag the
     * document for re-indexing — the regular {@link #update(String,
     * String, java.util.List, String, String, Boolean, Boolean)} call
     * cannot express "set to null" (its nullable boolean parameter
     * means "leave untouched"). Pass {@code null} to clear the override
     * (auto mode), {@code true}/{@code false} to force include/exclude.
     */
    public void setRagEnabledOverride(String id, @Nullable Boolean value) {
        Update u = new Update().set("ragDirty", true);
        if (value == null) {
            u.unset("ragEnabled");
        } else {
            u.set("ragEnabled", value);
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                u, DocumentDocument.class);
    }

    /**
     * Atomic update of the Script-Cortex deep-validate cache. Called
     * after an LLM review so subsequent UI loads can show a "still
     * reviewed" badge when the content hash matches.
     *
     * @param id            document id
     * @param contentHash   sha-256 hex of the {@code inlineText} that
     *                      was reviewed
     * @param warningsJson  serialized JSON array of warnings ({@code "[]"}
     *                      when the review found nothing)
     */
    public void setDeepReviewCache(String id, String contentHash, String warningsJson) {
        Update u = new Update()
                .set("lastDeepReviewedHash", contentHash)
                .set("lastDeepReviewWarningsJson", warningsJson)
                .set("lastDeepReviewedAt", Instant.now());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                u, DocumentDocument.class);
    }

    /**
     * Stamp a document's {@code kind} field. Used by the Script Cortex
     * controller to mark inline-text documents as {@code "script"} —
     * without this, {@link #applyHeader} (markdown-front-matter only)
     * leaves the field {@code null} for {@code .js}/{@code .json}
     * documents and the list-filter cannot find them.
     */
    public void setKind(String id, String kind) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().set("kind", kind),
                DocumentDocument.class);
    }

    /**
     * Removes the document and its storage blob (soft-delete on storage).
     * No-op if the id is unknown.
     *
     * <p>This is the <b>hard</b> delete path — the document row is gone.
     * For the recoverable LLM-tool delete, use {@link #trash(String)}
     * instead, which moves the document to {@value #TRASH_FOLDER_PREFIX}
     * inside the same project.
     */
    public void delete(String id) {
        delete(id, TOOL_IDENTITY);
    }

    /** Single-editorId-overload — used by callers that only have the editorId. */
    public void delete(String id, @Nullable String editorId) {
        delete(id, WriterIdentity.of(editorId, null, null));
    }

    /**
     * Same as {@link #delete(String)} but with full {@link WriterIdentity} —
     * forwarded into the live-broadcast event so the deleter's own
     * WebSocket is filtered out and subscribers can show {@code ⏺ name}.
     */
    public void delete(String id, WriterIdentity identity) {
        repository.findById(id).ifPresent(doc -> {
            String sid = doc.getStorageId();
            if (sid != null) {
                try {
                    storageService.delete(sid);
                } catch (Exception e) {
                    log.warn("Failed to delete storage blob for document id='{}' storageId='{}'",
                            id, sid, e);
                }
            }
            repository.delete(doc);
            // Wipe the version history along with the live row — keeps
            // archive entries (and their exclusively owned storage blobs)
            // from outliving the document they belong to.
            try {
                archiveService.deleteAllForLineage(
                        doc.getTenantId(), doc.getProjectId(), doc.getLineageId());
            } catch (Exception e) {
                log.warn("Failed to delete archive entries for document id='{}' lineageId='{}'",
                        id, doc.getLineageId(), e);
            }
            log.info("Deleted document id='{}' path='{}'", id, doc.getPath());
            publishDeleted(doc, identity);
        });
    }

    /** Trash-folder convention: every project has a virtual
     *  {@code _bin/} folder that holds soft-deleted documents.
     *  Names there always carry a UUID prefix so collisions with
     *  active documents (or earlier trash entries with the same
     *  basename) are impossible. Lives one level above
     *  {@code _vance/} so it's a peer of the project's own work,
     *  not nested under the system folder. */
    public static final String TRASH_FOLDER_PREFIX = "_bin/";

    /** Default folder for user-content documents. Search / list tools
     *  scope to this prefix by default so trash, kit manifests
     *  ({@code _vance/}), chat attachments ({@code _chatbox/}),
     *  Slartibartfast scratch ({@code _slart/}) and similar
     *  system-managed paths stay out of the LLM's noise. Callers can
     *  override by passing an explicit {@code pathPrefix} (including
     *  an empty string to search project-wide).
     *
     *  <p>Creation tools without an explicit path land here too —
     *  otherwise a freshly created doc would be invisible to the same
     *  default search the LLM uses to find it again. */
    public static final String DOCUMENTS_FOLDER_PREFIX = "documents/";

    /** Header key used by {@link #trash(String)} to remember where a
     *  trashed document lived before, so {@link #restore} can put it
     *  back without the caller having to track that separately. */
    public static final String TRASH_ORIGINAL_PATH_HEADER = "_trash-original-path";

    /**
     * Soft-delete a document by moving it into the project's trash
     * folder ({@code _bin/}). The document keeps its
     * id, body and metadata; only {@link DocumentDocument#getPath()}
     * and {@link DocumentDocument#getName()} change. The original
     * path is recorded in the document's header map under
     * {@link #TRASH_ORIGINAL_PATH_HEADER} so {@link #restore} can
     * find it.
     *
     * <p>Trashing an already-trashed document is a no-op (returns the
     * existing trash entry unchanged); the trash path is collision-
     * free by construction (UUID prefix), so a second trash would
     * just rewrite the same row.
     *
     * @return the document at its new trash path.
     * @throws IllegalArgumentException if the id is unknown.
     */
    public DocumentDocument trash(String id) {
        return trash(id, TOOL_IDENTITY);
    }

    /** Single-editorId-overload — used by callers that only have the editorId. */
    public DocumentDocument trash(String id, @Nullable String editorId) {
        return trash(id, WriterIdentity.of(editorId, null, null));
    }

    /**
     * Same as {@link #trash(String)} but with full {@link WriterIdentity} —
     * forwarded into the synthetic {@code DocumentLiveChangedEvent.DELETED}
     * event so the live-broadcast layer can skip the deleter's own WebSocket
     * and subscribers can render {@code ⏺ name}.
     */
    public DocumentDocument trash(String id, WriterIdentity identity) {
        DocumentDocument doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document id='" + id + "'"));
        if (isTrash(doc.getPath())) {
            log.debug("Document id='{}' is already in trash at path='{}'", id, doc.getPath());
            return doc;
        }
        String originalPath = doc.getPath();
        String basename = extractName(originalPath);
        String uuid = java.util.UUID.randomUUID().toString();
        String trashPath = TRASH_FOLDER_PREFIX + uuid + "_" + basename;

        // Remember the original path so restore() can reconstruct.
        // headers is a LinkedHashMap by Lombok default; mutate in
        // place is fine — applyHeader doesn't run for path-only
        // updates.
        java.util.Map<String, String> headers = doc.getHeaders();
        if (headers == null) {
            headers = new java.util.LinkedHashMap<>();
            doc.setHeaders(headers);
        }
        headers.put(TRASH_ORIGINAL_PATH_HEADER, originalPath);

        doc.setPath(trashPath);
        doc.setName(extractName(trashPath));
        DocumentDocument saved = repository.save(doc);
        log.info("Trashed document id='{}' from path='{}' to '{}'", id, originalPath, trashPath);
        // Cache-coherence: a trash makes the document disappear from its
        // original location, so listeners that key on the original path
        // (e.g. UrsaHookDocumentListener) need a Deleted event for it.
        // We synthesise one with the original path; the trash row itself
        // lives under _bin/… and is filtered out by isEventPublishable.
        publishDeleted(originalPath, doc.getTenantId(), doc.getProjectId(), saved.getId(),
                identity);
        return saved;
    }

    /**
     * Restore a trashed document. When {@code newPath} is
     * {@code null}, restoration uses the
     * {@link #TRASH_ORIGINAL_PATH_HEADER} value the trash step
     * recorded; pass an explicit path to override.
     *
     * <p>If the destination path is occupied (the original location
     * has been re-used since the trash), the call throws
     * {@link DocumentAlreadyExistsException}; the caller can pick a
     * different path and retry.
     *
     * @return the restored document.
     * @throws IllegalArgumentException when the id is unknown or when
     *         the document isn't in the trash folder.
     */
    public DocumentDocument restore(String id, @Nullable String newPath) {
        DocumentDocument doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document id='" + id + "'"));
        if (!isTrash(doc.getPath())) {
            throw new IllegalArgumentException(
                    "Document id='" + id + "' is not in the trash folder (path='"
                            + doc.getPath() + "')");
        }
        java.util.Map<String, String> headers = doc.getHeaders();
        String original = headers != null ? headers.get(TRASH_ORIGINAL_PATH_HEADER) : null;
        String target = newPath != null && !newPath.isBlank()
                ? normalizePath(newPath)
                : (original != null ? normalizePath(original) : null);
        if (target == null) {
            throw new IllegalArgumentException(
                    "Cannot restore: no original path recorded and no newPath given for id='" + id + "'");
        }
        if (repository.existsByTenantIdAndProjectIdAndPath(
                doc.getTenantId(), doc.getProjectId(), target)) {
            throw new DocumentAlreadyExistsException(
                    "Cannot restore to '" + target + "' — a document already lives there. "
                            + "Pass a different newPath.");
        }
        doc.setPath(target);
        doc.setName(extractName(target));
        if (headers != null) headers.remove(TRASH_ORIGINAL_PATH_HEADER);
        DocumentDocument saved = repository.save(doc);
        log.info("Restored document id='{}' from trash to path='{}'", id, target);
        // Mirror trash: a restore is an Upserted at the restored path so
        // listeners can pick the document back into their caches.
        return publishUpserted(saved);
    }

    /** {@code true} when the path lives under the project's trash
     *  folder. Useful for filtering trash out of regular listings. */
    public static boolean isTrash(@Nullable String path) {
        return path != null && path.startsWith(TRASH_FOLDER_PREFIX);
    }

    /** {@code true} when the path lives under the default
     *  user-documents folder ({@link #DOCUMENTS_FOLDER_PREFIX}). */
    public static boolean isInDocuments(@Nullable String path) {
        return path != null && path.startsWith(DOCUMENTS_FOLDER_PREFIX);
    }

    /** Magic value the search / list tools accept on
     *  {@code pathPrefix} to opt out of the default
     *  {@link #DOCUMENTS_FOLDER_PREFIX} scope and search project-wide
     *  (trash + system folders included). Cleaner LLM ergonomics than
     *  "pass an empty string" — the trim-on-read in
     *  {@code paramString} already collapses blank input to {@code null},
     *  so we couldn't tell "empty" from "missing" anyway. */
    public static final String SCOPE_ALL = "*";

    /**
     * Resolves a tool-supplied {@code pathPrefix} argument against the
     * documents-folder default. Returns the prefix actually used as a
     * filter:
     *
     * <ul>
     *   <li>{@code null} or blank → {@link #DOCUMENTS_FOLDER_PREFIX}
     *       (default scope; trash + system folders fall out).</li>
     *   <li>{@link #SCOPE_ALL} ({@code "*"}) → empty string (explicit
     *       project-wide search, no filter).</li>
     *   <li>any other value → that value verbatim.</li>
     * </ul>
     *
     * <p>Search / list tools call this on every invocation so the
     * default lives in one place rather than copied across each
     * {@code if (pathPrefix == null) pathPrefix = "documents/"}.
     */
    public static String resolveScope(@Nullable String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) return DOCUMENTS_FOLDER_PREFIX;
        if (SCOPE_ALL.equals(pathPrefix.trim())) return "";
        return pathPrefix;
    }

    /**
     * Lists the virtual folders inside a project — derived from the
     * {@code path} field of all active documents.
     *
     * @param parentPath optional prefix filter; {@code null} or empty returns
     *                   all folders at every depth
     */
    public List<FolderInfo> extractFolders(String tenantId, String projectId, @Nullable String parentPath) {
        String normalizedParent = parentPath == null || parentPath.isBlank()
                ? null
                : parentPath.replaceAll("/+$", "");

        // Project-distinct paths only — never load full documents (no
        // inlineText, no storage blob lookup) just to derive the folder
        // tree. Mirrors {@link #listFolders} but keeps per-folder counts.
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId)
                .and("status").is(DocumentStatus.ACTIVE));
        List<String> paths = mongoTemplate.findDistinct(
                query, "path", DocumentDocument.class, String.class);
        Map<String, FolderStats> folders = new HashMap<>();
        for (String path : paths) {
            for (String folder : foldersOfPath(path)) {
                if (normalizedParent != null
                        && !folder.equals(normalizedParent)
                        && !folder.startsWith(normalizedParent + "/")) {
                    continue;
                }
                folders.computeIfAbsent(folder, k -> new FolderStats()).documentCount++;
            }
        }
        for (Map.Entry<String, FolderStats> entry : folders.entrySet()) {
            String folder = entry.getKey();
            int subs = 0;
            for (String candidate : folders.keySet()) {
                if (candidate.equals(folder)) continue;
                if (!candidate.startsWith(folder + "/")) continue;
                if (candidate.substring(folder.length() + 1).indexOf('/') >= 0) continue;
                subs++;
            }
            entry.getValue().subfolderCount = subs;
        }

        List<FolderInfo> result = new ArrayList<>(folders.size());
        for (Map.Entry<String, FolderStats> entry : folders.entrySet()) {
            String folder = entry.getKey();
            int slash = folder.lastIndexOf('/');
            String name = slash < 0 ? folder : folder.substring(slash + 1);
            String parent = slash < 0 ? null : folder.substring(0, slash);
            result.add(new FolderInfo(
                    folder,
                    name,
                    parent,
                    entry.getValue().documentCount,
                    entry.getValue().subfolderCount));
        }
        result.sort(Comparator.comparing(FolderInfo::path));
        return result;
    }

    /**
     * Parse the front matter of {@code doc.inlineText} and mirror the result
     * onto the entity's {@code kind} / {@code headers} fields. The mime-type
     * picks the {@link HeaderStrategy} (markdown front-matter, YAML
     * multi-document stream, JSON {@code $meta} object); unsupported
     * mime-types and unparsable bodies clear the projection.
     *
     * <p>Truth lives in {@code inlineText}; this method rebuilds the index
     * on every save and is never written back from.
     */
    private void applyHeader(DocumentDocument doc) {
        Optional<DocumentHeader> parsed;
        if (doc.getStorageId() != null) {
            try (InputStream in = loadContent(doc)) {
                parsed = headerParser.parseStream(doc.getMimeType(), in);
            } catch (IOException e) {
                log.warn("Failed to stream document for header parsing id='{}' path='{}': {}",
                        doc.getId(), doc.getPath(), e.toString());
                parsed = Optional.empty();
            }
        } else {
            parsed = Optional.empty();
        }

        if (parsed.isEmpty()) {
            doc.setKind(null);
            doc.setHeaders(new java.util.LinkedHashMap<>());
            return;
        }
        DocumentHeader header = parsed.get();
        doc.setKind(header.getKind());
        doc.setHeaders(new java.util.LinkedHashMap<>(header.getValues()));
        applySystemTags(doc);
    }

    /**
     * Merge system-derived tags into {@code doc.tags} so that the
     * picker list, search, and tag-based queries can identify
     * special documents without parsing the body. User-set tags are
     * preserved; only missing system tags are appended.
     *
     * <p>For now this covers {@code kind: application} documents
     * (like a folder's {@code _app.yaml}) — they get an
     * {@code application} tag and a second tag carrying the {@code app}
     * type (e.g. {@code kanban}, {@code calendar}). Other kinds may
     * follow the same pattern; this method is the single hook.
     */
    private static void applySystemTags(DocumentDocument doc) {
        String kind = doc.getKind();
        if (kind == null || kind.isBlank()) return;
        List<String> tags = doc.getTags();
        if (tags == null) {
            tags = new ArrayList<>();
            doc.setTags(tags);
        }
        if ("application".equals(kind)) {
            addTagIfMissing(tags, "application");
            String app = doc.getHeaders() == null ? null : doc.getHeaders().get("app");
            if (app != null && !app.isBlank()) {
                addTagIfMissing(tags, app.trim());
            }
        }
    }

    private static void addTagIfMissing(List<String> tags, String value) {
        for (String existing : tags) {
            if (value.equals(existing)) return;
        }
        tags.add(value);
    }

    private static String normalizePath(String path) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        String trimmed = path.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("path must not be blank");
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty()) throw new IllegalArgumentException("path must not be blank");
        return trimmed;
    }

    private static String extractName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Mime-types the server treats as inline-eligible plain text —
     * if the payload also fits the {@code inline-threshold}, it goes
     * into {@code inlineText} rather than the storage blob layer.
     *
     * <p>Any {@code text/*} type qualifies automatically. The
     * additional whitelist here covers code / config formats whose
     * IANA / browser-default mime-type is {@code application/...}
     * but which are conceptually text — JavaScript, JSON, YAML,
     * Python, Shell, R, SQL, XML, etc. Keeps source / config files
     * editable in the inline-text web UI.
     */
    private static final java.util.Set<String> CODE_MIME_WHITELIST = java.util.Set.of(
            // Doc / config
            "application/json",
            "application/yaml",
            "application/x-yaml",
            "application/xml",
            // Scripts / source
            "application/javascript",
            "application/x-javascript",
            "application/typescript",
            "application/x-typescript",
            "application/x-python",
            "application/x-sh",
            "application/x-bash",
            "application/x-shellscript",
            "application/x-r",
            "application/sql",
            "application/x-java-source",
            "application/xhtml+xml");

    /**
     * Whether a freshly-created document with {@code mimeType} should
     * default to {@code autoSummary = true}. Conservative v1 list:
     * {@code text/markdown} and {@code text/plain} only. PDF, images
     * and code formats stay out — users can flip the flag manually if
     * they want.
     */
    private static boolean isAutoSummaryEligible(@Nullable String mimeType) {
        if (mimeType == null) return false;
        String mt = mimeType.toLowerCase().trim();
        int semi = mt.indexOf(';');
        if (semi >= 0) mt = mt.substring(0, semi).trim();
        return "text/markdown".equals(mt) || "text/plain".equals(mt);
    }

    private static boolean isTextual(@Nullable String mimeType) {
        if (mimeType == null) return false;
        String mt = mimeType.toLowerCase().trim();
        // Strip any "; charset=…" suffix browsers / clients sometimes
        // tack onto upload form-data.
        int semi = mt.indexOf(';');
        if (semi >= 0) mt = mt.substring(0, semi).trim();
        if (mt.startsWith("text/")) return true;
        return CODE_MIME_WHITELIST.contains(mt);
    }

    /** All ancestor folder paths of {@code path} (exclusive of the file itself). */
    private static List<String> foldersOfPath(String path) {
        List<String> folders = new ArrayList<>();
        String[] parts = path.split("/");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) current.append('/');
            current.append(parts[i]);
            folders.add(current.toString());
        }
        return folders;
    }

    private static final class FolderStats {
        int documentCount;
        int subfolderCount;
    }

    // ────────────────────────────────────────────────────────────────────
    // Orphan-cleanup support — read-only batch queries used by
    // StorageOrphanCleanupService. Both methods project a single field
    // so the response stays at O(batchSize) regardless of document size.
    // ────────────────────────────────────────────────────────────────────

    /**
     * Of the given {@code storageIds}, returns the subset that at least one
     * live document still references. The complement is "no document points
     * to this storage" — the cleanup-sweep then asks the archive side too
     * before declaring the blob an orphan.
     */
    public java.util.Set<String> findReferencedStorageIds(
            java.util.Collection<String> storageIds) {
        if (storageIds == null || storageIds.isEmpty()) return java.util.Set.of();
        Query q = new Query(Criteria.where("storageId").in(storageIds));
        q.fields().include("storageId");
        java.util.Set<String> found = new java.util.HashSet<>();
        for (DocumentDocument d : mongoTemplate.find(q, DocumentDocument.class)) {
            if (d.getStorageId() != null) found.add(d.getStorageId());
        }
        return found;
    }

    /**
     * Of the given {@code lineageIds}, returns the subset for which at least
     * one live document still exists. The complement is "no live document
     * left in this lineage" — those lineage's archives are orphans that the
     * cleanup-sweep should remove.
     */
    public java.util.Set<String> findLineageIdsWithLiveDocument(
            java.util.Collection<String> lineageIds) {
        if (lineageIds == null || lineageIds.isEmpty()) return java.util.Set.of();
        Query q = new Query(Criteria.where("lineageId").in(lineageIds));
        q.fields().include("lineageId");
        java.util.Set<String> found = new java.util.HashSet<>();
        for (DocumentDocument d : mongoTemplate.find(q, DocumentDocument.class)) {
            if (d.getLineageId() != null) found.add(d.getLineageId());
        }
        return found;
    }

    public static class DocumentAlreadyExistsException extends RuntimeException {
        public DocumentAlreadyExistsException(String message) {
            super(message);
        }
    }

    // ──────────────────── Event-bus publishing ────────────────────
    //
    // Fired right after a successful Mongo write (and the storage-side
    // effects that go with it) so downstream caches — ServerToolRegistry,
    // UrsaScheduler, UrsaHook, … — can refresh themselves through a
    // {@code DocumentChangedEvent} listener instead of the Admin-Service
    // having to know which subsystem cares. See
    // {@code planning/document-change-events.md}.
    //
    // Publish failures are swallowed: Mongo is the source of truth. A
    // broken listener (or a missing publisher in a test context) must not
    // unwind the write the user already saw succeed.

    /**
     * Folder prefix every config-document path begins with. Only writes
     * under this prefix matter for cache-coherence — user documents
     * ({@code documents/...}), chat attachments ({@code _chatbox/...}),
     * trash ({@code _bin/...}) and Slartibartfast scratch
     * ({@code _slart/...}) never feed any in-memory registry. Keeping
     * the event bus to just config writes makes downstream listeners
     * trivially cheap (their first line is a {@code startsWith}).
     */
    static final String EVENT_PUBLISH_INCLUDE_PREFIX = "_vance/";

    /**
     * Sub-prefix of {@link #EVENT_PUBLISH_INCLUDE_PREFIX} that holds
     * ephemeral run logs (scheduler ticks, UrsaEvent trigger replays).
     * Excluded from the event bus because the volume is high and no
     * cache reads from these paths.
     */
    static final String EVENT_PUBLISH_EXCLUDE_LOGS_PREFIX = "_vance/logs/";

    /**
     * Path prefixes that never fan out as {@link DocumentLiveChangedEvent}.
     * Higher volume than the narrow cache event and zero user-facing
     * subscribers expected on these paths. Anything not on this list IS
     * eligible — including {@code documents/...} and {@code _vance/...}.
     */
    static final java.util.List<String> LIVE_EVENT_EXCLUDE_PREFIXES = java.util.List.of(
            EVENT_PUBLISH_EXCLUDE_LOGS_PREFIX,
            "_bin/",
            "_slart/",
            "_chatbox/");

    /**
     * Decides whether a document path should fan out as a
     * {@link DocumentChangedEvent}. Visible for tests.
     */
    static boolean isEventPublishable(@Nullable String path) {
        if (path == null) return false;
        if (!path.startsWith(EVENT_PUBLISH_INCLUDE_PREFIX)) return false;
        return !path.startsWith(EVENT_PUBLISH_EXCLUDE_LOGS_PREFIX);
    }

    /**
     * Decides whether a path should fan out as a
     * {@link DocumentLiveChangedEvent} (live-WS push). Wider than
     * {@link #isEventPublishable}: includes everything not on the
     * {@link #LIVE_EVENT_EXCLUDE_PREFIXES} noise list. Visible for tests.
     */
    static boolean isLiveEventPublishable(@Nullable String path) {
        if (path == null) return false;
        for (String prefix : LIVE_EVENT_EXCLUDE_PREFIXES) {
            if (path.startsWith(prefix)) return false;
        }
        return true;
    }

    /**
     * Sentinel editor identity attached to writes that originate
     * server-side — LLM-invoked tools, scripts, Slartibartfast,
     * schedulers, kit installers, restore-from-trash. Distinct from any
     * real client editorId (those are random UUIDs from the WS handshake),
     * so the live-broadcast layer never accidentally suppresses a
     * legitimate viewer, while logs and the wire-payload stay informative
     * about the origin of the write.
     */
    public static final String EDITOR_ID_TOOL = "_tool";

    /**
     * Identity of the writer attached to a document mutation — propagated
     * through {@link DocumentLiveChangedEvent} into the live-broadcast
     * wire payload, so subscribers can render an "⏺ Bob" awareness
     * badge after a silent merge. All three fields are nullable; the
     * REST controller fills them from the request's JWT + the
     * {@code X-Editor-Id} header. Tool / script / scheduler paths use
     * {@link #TOOL_IDENTITY}.
     */
    public record WriterIdentity(
            @Nullable String editorId,
            @Nullable String userId,
            @Nullable String displayName) {
        public static WriterIdentity of(
                @Nullable String editorId,
                @Nullable String userId,
                @Nullable String displayName) {
            return new WriterIdentity(editorId, userId, displayName);
        }
    }

    /**
     * Identity placeholder for writes that don't come from a real
     * editor — LLM tools, scripts, schedulers, kit installers. The
     * {@code editorId} carries the {@link #EDITOR_ID_TOOL} sentinel;
     * {@code userId}/{@code displayName} are left {@code null} so the
     * client-side badge doesn't render a misleading user name.
     */
    public static final WriterIdentity TOOL_IDENTITY =
            WriterIdentity.of(EDITOR_ID_TOOL, null, null);

    /**
     * Default overload — assumes the write produced a real change worth
     * a live broadcast (true for create, content-replace, restore-from-trash:
     * cases where the body/storage moved) and uses the
     * {@link #TOOL_IDENTITY} sentinel as the writer. Caller paths that
     * have a real client identity (REST writes with X-Editor-Id + JWT)
     * must use the WriterIdentity overload so the writer's own
     * connection is filtered out of the local fan-out and the
     * "⏺ name" badge gets a useful display name.
     */
    private DocumentDocument publishUpserted(DocumentDocument saved) {
        return publishUpserted(saved, true, TOOL_IDENTITY);
    }

    private DocumentDocument publishUpserted(DocumentDocument saved, boolean contentChanged) {
        return publishUpserted(saved, contentChanged, TOOL_IDENTITY);
    }

    /** Legacy single-arg-editorId overload — kept for callers that have
     *  only the editorId on hand (no user info). Defaults user fields to null. */
    private DocumentDocument publishUpserted(DocumentDocument saved, boolean contentChanged,
            @Nullable String editorId) {
        return publishUpserted(saved, contentChanged,
                WriterIdentity.of(editorId, null, null));
    }

    private DocumentDocument publishUpserted(DocumentDocument saved, boolean contentChanged,
            WriterIdentity identity) {
        ApplicationEventPublisher publisher = this.eventPublisher;
        if (publisher == null) return saved;
        log.trace("publishUpserted tenantId='{}' projectId='{}' path='{}' contentChanged={} liveEligible={} writer={}",
                saved.getTenantId(), saved.getProjectId(), saved.getPath(),
                contentChanged, isLiveEventPublishable(saved.getPath()), identity);
        if (isEventPublishable(saved.getPath())) {
            try {
                publisher.publishEvent(new DocumentChangedEvent.Upserted(
                        saved.getTenantId(),
                        saved.getProjectId(),
                        saved.getPath(),
                        saved.getId()));
            } catch (RuntimeException ex) {
                log.warn("DocumentService: publish Upserted failed for '{}/{}/{}': {}",
                        saved.getTenantId(), saved.getProjectId(), saved.getPath(),
                        ex.toString());
            }
        }
        if (contentChanged && isLiveEventPublishable(saved.getPath())) {
            try {
                publisher.publishEvent(new DocumentLiveChangedEvent(
                        saved.getTenantId(),
                        saved.getProjectId(),
                        saved.getPath(),
                        DocumentLiveChangedEvent.Kind.UPSERTED,
                        identity.editorId(),
                        identity.userId(),
                        identity.displayName()));
            } catch (RuntimeException ex) {
                log.warn("DocumentService: publish LiveUpserted failed for '{}/{}/{}': {}",
                        saved.getTenantId(), saved.getProjectId(), saved.getPath(),
                        ex.toString());
            }
        }
        return saved;
    }

    private void publishDeleted(DocumentDocument doc, WriterIdentity identity) {
        publishDeleted(doc.getPath(), doc.getTenantId(), doc.getProjectId(), doc.getId(),
                identity);
    }

    /**
     * Overload that takes an explicit {@code path} — used by
     * {@link #trash(String)} where the document row still exists but the
     * <em>logical</em> location has moved away. Listeners keyed on the
     * original path need a Deleted event for it.
     */
    private void publishDeleted(String path, String tenantId, String projectId,
                                @Nullable String documentId,
                                WriterIdentity identity) {
        ApplicationEventPublisher publisher = this.eventPublisher;
        if (publisher == null) return;
        if (isEventPublishable(path)) {
            try {
                publisher.publishEvent(new DocumentChangedEvent.Deleted(
                        tenantId, projectId, path, documentId));
            } catch (RuntimeException ex) {
                log.warn("DocumentService: publish Deleted failed for '{}/{}/{}': {}",
                        tenantId, projectId, path, ex.toString());
            }
        }
        if (isLiveEventPublishable(path)) {
            try {
                publisher.publishEvent(new DocumentLiveChangedEvent(
                        tenantId, projectId, path,
                        DocumentLiveChangedEvent.Kind.DELETED,
                        identity.editorId(),
                        identity.userId(),
                        identity.displayName()));
            } catch (RuntimeException ex) {
                log.warn("DocumentService: publish LiveDeleted failed for '{}/{}/{}': {}",
                        tenantId, projectId, path, ex.toString());
            }
        }
    }

    // ─── sticky notes ──────────────────────────────────────────────────
    //
    // Notes are mutated through atomic MongoTemplate ops — never via
    // repository.save(doc) — so a note CRUD never touches the document
    // body, never bumps storageId/updatedAt, and never triggers the
    // archive-on-save logic. The notes map is also unchanged in shape
    // from the editor's perspective: storage migration + summary
    // pipelines simply don't observe it.
    //
    // See specification/documents-channel.md is not the home; notes
    // have their own (forthcoming) spec — for now the design is captured
    // in DocumentNote's class doc.

    /** Hard cap — additional notes are rejected once a document reaches this. */
    public static final int NOTES_MAX = 1000;

    public static class NotesLimitExceededException extends RuntimeException {
        public NotesLimitExceededException(String docId) {
            super("Document id='" + docId + "' already has " + NOTES_MAX + " notes");
        }
    }

    /**
     * Append a new note to {@code docId}. Generates a fresh UUID for the
     * note. Atomic against the {@link #NOTES_MAX} cap via an {@code $expr}
     * size-check inside the {@code findAndModify} criteria; concurrent
     * adds that would push past the limit lose the race and throw
     * {@link NotesLimitExceededException}.
     *
     * @return the persisted note with its id + timestamps populated.
     * @throws IllegalArgumentException when the document is unknown.
     */
    public DocumentNote addNote(
            String docId,
            String text,
            String userId,
            @Nullable Integer line) {
        if (!repository.existsById(docId)) {
            throw new IllegalArgumentException("Unknown document id='" + docId + "'");
        }
        Instant now = Instant.now();
        DocumentNote note = DocumentNote.builder()
                .id(java.util.UUID.randomUUID().toString())
                .text(text)
                .userId(userId)
                .createdAt(now)
                .updatedAt(now)
                .done(false)
                .line(line)
                .build();

        // $expr: { $lt: [ { $size: { $objectToArray: { $ifNull: [ "$notes", {} ] } } }, NOTES_MAX ] }
        org.bson.Document sizeExpr = new org.bson.Document("$lt", java.util.List.of(
                new org.bson.Document("$size", new org.bson.Document("$objectToArray",
                        new org.bson.Document("$ifNull", java.util.List.of("$notes",
                                new org.bson.Document())))),
                NOTES_MAX));
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(docId),
                new Criteria("$expr").is(sizeExpr)));
        Update update = new Update().set("notes." + note.getId(), note);

        DocumentDocument modified = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true),
                DocumentDocument.class);
        if (modified == null) {
            // Either the document is gone (covered above) or the cap was hit.
            throw new NotesLimitExceededException(docId);
        }
        log.debug("Added note id='{}' to document id='{}' by user='{}'",
                note.getId(), docId, userId);
        return note;
    }

    /**
     * Patch {@code text} / {@code done} on an existing note. Either field
     * may be {@code null} to leave it untouched. Bumps {@link DocumentNote#getUpdatedAt()}.
     *
     * @return the patched note, or empty if the document or note id is unknown.
     */
    /**
     * Patch one or more fields on an existing note. {@code null} on a
     * field means "leave untouched"; passing an explicit value (including
     * empty string for {@code text}) writes it.
     *
     * <p>{@code line} uses a sentinel value of {@link Integer#MIN_VALUE} to
     * mean "explicitly clear / unset" because plain {@code null} already
     * means "leave untouched". Pragmatic: callers rarely need the unset
     * path; the line field stays where it was on every typical update.
     *
     * @return the patched note, or empty if the document or note id is unknown.
     */
    public Optional<DocumentNote> updateNote(
            String docId,
            String noteId,
            @Nullable String newText,
            @Nullable Boolean newDone,
            @Nullable Integer newLine) {
        Update update = new Update().set("notes." + noteId + ".updatedAt", Instant.now());
        if (newText != null) update.set("notes." + noteId + ".text", newText);
        if (newDone != null) update.set("notes." + noteId + ".done", newDone);
        if (newLine != null) {
            if (newLine == Integer.MIN_VALUE) {
                update.unset("notes." + noteId + ".line");
            } else {
                update.set("notes." + noteId + ".line", newLine);
            }
        }

        Query query = Query.query(Criteria.where("_id").is(docId)
                .and("notes." + noteId).exists(true));
        DocumentDocument modified = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true),
                DocumentDocument.class);
        if (modified == null) return Optional.empty();
        log.debug("Updated note id='{}' on document id='{}'", noteId, docId);
        return Optional.ofNullable(modified.getNotes()).map(m -> m.get(noteId));
    }

    /**
     * Drop a note from {@code docId}. Idempotent — removing a missing
     * note is a silent no-op. Returns {@code true} when the note existed
     * and was removed, {@code false} otherwise.
     */
    public boolean deleteNote(String docId, String noteId) {
        Query query = Query.query(Criteria.where("_id").is(docId)
                .and("notes." + noteId).exists(true));
        Update update = new Update().unset("notes." + noteId);
        long modified = mongoTemplate.updateFirst(query, update, DocumentDocument.class)
                .getModifiedCount();
        if (modified > 0) {
            log.debug("Deleted note id='{}' from document id='{}'", noteId, docId);
            return true;
        }
        return false;
    }

    /**
     * Snapshot of all notes attached to {@code docId}. Returns an empty
     * list when the document is unknown or has no notes. Map order is
     * preserved (insertion-order via {@code LinkedHashMap}).
     */
    public List<DocumentNote> listNotes(String docId) {
        return repository.findById(docId)
                .map(DocumentDocument::getNotes)
                .map(m -> new ArrayList<>(m.values()))
                .map(list -> (List<DocumentNote>) list)
                .orElse(java.util.Collections.emptyList());
    }
}
