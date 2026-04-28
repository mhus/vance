package de.mhus.vance.shared.document;

import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private final DocumentRepository repository;
    private final StorageService storageService;

    @Value("${vance.document.inline-threshold:4096}")
    private int inlineThreshold;

    public Optional<DocumentDocument> findById(String id) {
        return repository.findById(id);
    }

    public Optional<DocumentDocument> findByPath(String tenantId, String projectId, String path) {
        return repository.findByTenantIdAndProjectIdAndPath(tenantId, projectId, normalizePath(path));
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
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by("path").ascending());
        return repository.findByTenantIdAndProjectIdAndStatus(
                tenantId, projectId, DocumentStatus.ACTIVE, pageable);
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
                .build();

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
     * Opens a streaming read over the document's content. Caller closes.
     * Returns an empty stream for documents that have neither inline text nor
     * a storage blob (shouldn't happen, but defensive).
     */
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
     * Storage-backed documents are read-only in v1.
     *
     * @return the updated document
     * @throws IllegalArgumentException if the document is unknown or the update
     *         is rejected (storage-backed content, oversize inline text)
     */
    public DocumentDocument update(
            String id,
            @Nullable String newTitle,
            @Nullable List<String> newTags,
            @Nullable String newInlineText) {

        DocumentDocument doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document id='" + id + "'"));

        if (newTitle != null) doc.setTitle(newTitle);
        if (newTags != null) doc.setTags(new ArrayList<>(newTags));

        if (newInlineText != null) {
            if (doc.getInlineText() == null) {
                throw new IllegalArgumentException(
                        "Document id='" + id + "' is storage-backed and cannot be edited inline");
            }
            byte[] bytes = newInlineText.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > inlineThreshold) {
                throw new IllegalArgumentException(
                        "New inline content exceeds the inline threshold ("
                                + bytes.length + " > " + inlineThreshold + " bytes)");
            }
            doc.setInlineText(newInlineText);
            doc.setSize(bytes.length);
        }

        DocumentDocument saved = repository.save(doc);
        log.info("Updated document tenantId='{}' projectId='{}' id='{}' fields={}",
                saved.getTenantId(), saved.getProjectId(), saved.getId(),
                describeChanges(newTitle, newTags, newInlineText));
        return saved;
    }

    private static String describeChanges(@Nullable String title, @Nullable List<String> tags, @Nullable String inlineText) {
        StringBuilder sb = new StringBuilder("[");
        if (title != null) sb.append("title");
        if (tags != null) { if (sb.length() > 1) sb.append(','); sb.append("tags"); }
        if (inlineText != null) { if (sb.length() > 1) sb.append(','); sb.append("inlineText"); }
        return sb.append(']').toString();
    }

    /**
     * Removes the document and its storage blob (soft-delete on storage).
     * No-op if the id is unknown.
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

        List<DocumentDocument> docs = listByProject(tenantId, projectId);
        Map<String, FolderStats> folders = new HashMap<>();
        for (DocumentDocument doc : docs) {
            for (String folder : foldersOfPath(doc.getPath())) {
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

    private static boolean isTextual(@Nullable String mimeType) {
        return mimeType != null && mimeType.startsWith("text/");
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
