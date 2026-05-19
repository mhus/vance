package de.mhus.vance.shared.document;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${vance.document.inline-threshold:40960}")
    private int inlineThreshold;

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
     * Creates a document. {@code content} is consumed; close it yourself if you opened it.
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

        String normalizedPath = normalizePath(path);
        if (repository.existsByTenantIdAndProjectIdAndPath(tenantId, projectId, normalizedPath)) {
            throw new DocumentAlreadyExistsException(
                    "Document '" + normalizedPath + "' already exists in "
                            + tenantId + "/" + projectId);
        }

        byte[] probe;
        try {
            probe = content.readNBytes(inlineThreshold + 1);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read document content", e);
        }

        String inlineText = null;
        String storageId = null;
        long size;

        if (isTextual(mimeType) && probe.length <= inlineThreshold) {
            inlineText = new String(probe, StandardCharsets.UTF_8);
            size = probe.length;
        } else {
            try (InputStream combined = new SequenceInputStream(
                    new ByteArrayInputStream(probe), content)) {
                StorageService.StorageInfo info = storageService.store(tenantId, normalizedPath, combined);
                storageId = info.id();
                size = info.size();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to stream document content to storage", e);
            }
        }

        DocumentDocument doc = DocumentDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .path(normalizedPath)
                .name(extractName(normalizedPath))
                .title(title)
                .tags(tags == null ? new ArrayList<>() : new ArrayList<>(tags))
                .mimeType(mimeType)
                .size(size)
                .storageId(storageId)
                .inlineText(inlineText)
                .createdBy(createdBy)
                .status(DocumentStatus.ACTIVE)
                .autoSummary(isAutoSummaryEligible(mimeType))
                .build();
        applyHeader(doc);
        // Mark for RAG indexing if the document is eligible — the
        // project-RAG indexer picks dirty docs on its next tick.
        doc.setRagDirty(isRagEligible(doc));

        DocumentDocument saved = repository.save(doc);
        log.info("Created document tenantId='{}' projectId='{}' path='{}' id='{}' inline={} size={}",
                saved.getTenantId(), saved.getProjectId(), saved.getPath(), saved.getId(),
                saved.getInlineText() != null, saved.getSize());
        return saved;
    }

    /** Convenience for text payloads that are known to be in-memory. */
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
                "text/plain",
                new ByteArrayInputStream(bytes),
                createdBy);
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
        String inline = doc.getInlineText();
        if (inline != null) return inline;
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
        String inline = doc.getInlineText();
        if (inline != null) {
            return new ByteArrayInputStream(inline.getBytes(StandardCharsets.UTF_8));
        }
        String sid = doc.getStorageId();
        if (sid != null) {
            InputStream stream = storageService.load(sid);
            if (stream != null) {
                return stream;
            }
            log.warn("StorageService returned null for document id='{}' storageId='{}'",
                    doc.getId(), sid);
        }
        return InputStream.nullInputStream();
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

        DocumentDocument doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document id='" + id + "'"));

        if (newTitle != null) doc.setTitle(newTitle);
        if (newTags != null) doc.setTags(new ArrayList<>(newTags));
        if (newAutoSummary != null) doc.setAutoSummary(newAutoSummary);
        if (newSummaryDirty != null) doc.setSummaryDirty(newSummaryDirty);
        if (newRagEnabled != null) doc.setRagEnabled(newRagEnabled);

        if (newInlineText != null) {
            byte[] bytes = newInlineText.getBytes(StandardCharsets.UTF_8);
            boolean contentChanged = !newInlineText.equals(doc.getInlineText());
            boolean fitsInline = isTextual(doc.getMimeType())
                    && bytes.length <= inlineThreshold;
            String oldStorageId = doc.getStorageId();

            if (fitsInline) {
                // Target: inline. If the previous backing was
                // storage, write-the-inline + delete the old blob
                // (storage→inline transition).
                doc.setInlineText(newInlineText);
                doc.setStorageId(null);
                doc.setSize(bytes.length);
                if (oldStorageId != null) {
                    deleteStorageBlobQuietly(oldStorageId, id);
                }
            } else {
                // Target: storage. Write the new blob first, then
                // null out inline + delete old blob (handles all
                // three transitions: inline→storage, storage→storage,
                // inline-too-big rewrite). The "write new before
                // delete old" order means a crash mid-update leaves
                // both blobs — orphan reclaim handles that.
                StorageService.StorageInfo info;
                try (InputStream in = new ByteArrayInputStream(bytes)) {
                    info = storageService.store(
                            doc.getTenantId(), doc.getPath(), in);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Failed to stream updated document content to "
                                    + "storage for id='" + id + "'", e);
                }
                doc.setInlineText(null);
                doc.setStorageId(info.id());
                doc.setSize(info.size());
                if (oldStorageId != null
                        && !oldStorageId.equals(info.id())) {
                    deleteStorageBlobQuietly(oldStorageId, id);
                }
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
                describeChanges(newTitle, newTags, newInlineText, newPath));
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
            @Nullable String path) {
        StringBuilder sb = new StringBuilder("[");
        if (title != null) sb.append("title");
        if (tags != null) { if (sb.length() > 1) sb.append(','); sb.append("tags"); }
        if (inlineText != null) { if (sb.length() > 1) sb.append(','); sb.append("inlineText"); }
        if (path != null) { if (sb.length() > 1) sb.append(','); sb.append("path"); }
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
            log.info("Deleted document id='{}' path='{}'", id, doc.getPath());
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
        return saved;
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
        if (doc.getInlineText() == null) {
            doc.setKind(null);
            doc.setHeaders(new java.util.LinkedHashMap<>());
            return;
        }
        Optional<DocumentHeader> parsed = headerParser.parse(
                doc.getMimeType(), doc.getInlineText());
        if (parsed.isEmpty()) {
            doc.setKind(null);
            doc.setHeaders(new java.util.LinkedHashMap<>());
            return;
        }
        DocumentHeader header = parsed.get();
        doc.setKind(header.getKind());
        doc.setHeaders(new java.util.LinkedHashMap<>(header.getValues()));
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

    public static class DocumentAlreadyExistsException extends RuntimeException {
        public DocumentAlreadyExistsException(String message) {
            super(message);
        }
    }
}
