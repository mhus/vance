package de.mhus.vance.brain.documents;

import de.mhus.vance.api.documents.DocumentArchiveDto;
import de.mhus.vance.api.documents.DocumentArchiveListResponse;
import de.mhus.vance.api.documents.DocumentArchiveSummary;
import de.mhus.vance.api.documents.DocumentCreateRequest;
import de.mhus.vance.api.documents.DocumentDto;
import de.mhus.vance.api.documents.DocumentFolderListResponse;
import de.mhus.vance.api.documents.DocumentFoldersResponse;
import de.mhus.vance.api.documents.DocumentKindsResponse;
import de.mhus.vance.api.documents.DocumentListResponse;
import de.mhus.vance.api.documents.DocumentSummary;
import de.mhus.vance.api.documents.DocumentSummaryRequest;
import de.mhus.vance.api.documents.DocumentUpdateRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentArchiveDocument;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for documents — list (paged), detail, update.
 *
 * <p>Tenant in the path is validated by {@link
 * de.mhus.vance.brain.access.BrainAccessFilter} against the JWT's
 * {@code tid} claim. The {@code projectId} query-parameter is the project's
 * business name (not the Mongo id).
 *
 * <p>v1 only supports editing inline-stored documents — storage-backed content
 * is read-only here. See {@code specification/web-ui.md} §3.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

    private final DocumentService documentService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/documents")
    public DocumentListResponse list(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "pathPrefix", required = false) @Nullable String pathPrefix,
            @RequestParam(value = "kind", required = false) @Nullable String kind,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        Page<DocumentDocument> result = documentService.listByProjectPaged(
                tenant, projectId, page, size, pathPrefix, kind);
        return DocumentListResponse.builder()
                .items(result.getContent().stream().map(DocumentController::toSummary).toList())
                .page(result.getNumber())
                .pageSize(result.getSize())
                .totalCount(result.getTotalElements())
                .build();
    }

    /**
     * Folder-view endpoint for the documents browser. Returns the
     * subfolders directly under the requested {@code path} plus a
     * paged list of files in that same folder (no nesting). Path
     * defaults to project root when omitted. Folders are sorted
     * alphabetically; files by path ascending.
     */
    @GetMapping("/brain/{tenant}/documents/folder")
    public DocumentFolderListResponse folderView(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "path", required = false) @Nullable String path,
            @RequestParam(value = "search", required = false) @Nullable String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        DocumentService.FolderListing listing =
                documentService.listByFolder(tenant, projectId, path, search, page, size);
        return DocumentFolderListResponse.builder()
                .folders(listing.folders())
                .files(listing.files().stream().map(DocumentController::toSummary).toList())
                .page(listing.page())
                .pageSize(listing.pageSize())
                .totalCount(listing.totalFiles())
                .build();
    }

    /**
     * Folder list for the path-filter combobox. Lightweight —
     * reads only the {@code path} field via a Mongo projection,
     * so even a project with thousands of documents resolves
     * fast.
     */
    @GetMapping("/brain/{tenant}/documents/folders")
    public DocumentFoldersResponse folders(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        List<String> folders = documentService.listFolders(tenant, projectId);
        return DocumentFoldersResponse.builder().folders(folders).build();
    }

    /**
     * Distinct document {@code kind} values present in the project — same
     * lightweight contract as {@link #folders}, derived from a Mongo
     * projection on the indexed {@code kind} field. Powers the kind-filter
     * dropdown in the document list.
     */
    @GetMapping("/brain/{tenant}/documents/kinds")
    public DocumentKindsResponse kinds(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        List<String> kinds = documentService.listKinds(tenant, projectId);
        return DocumentKindsResponse.builder().kinds(kinds).build();
    }

    /**
     * Find one document by ({@code projectId}, {@code path}). Powers the
     * Web-UI embedded-link resolver (Markdown links with {@code vance:}
     * URI) — see specification/inline-and-embedded-content.md §11.8.
     *
     * <p>Path must match exactly. Returns 404 when no document with the
     * given path exists.
     */
    @GetMapping("/brain/{tenant}/documents/by-path")
    public DocumentDto findByPath(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        DocumentDocument doc = documentService.findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        authority.enforce(httpRequest,
                new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.READ);
        return toDto(doc);
    }

    @GetMapping("/brain/{tenant}/documents/{id}")
    public DocumentDto findOne(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {

        DocumentDocument doc = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(doc.getTenantId())) {
            // The filter ensures the JWT's tenant matches the path; this guards
            // against a caller fetching a document that lives in a different
            // tenant by guessing its id.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        authority.enforce(httpRequest,
                new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.READ);
        return toDto(doc);
    }

    @PostMapping("/brain/{tenant}/documents")
    public ResponseEntity<DocumentDto> create(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @Valid @RequestBody DocumentCreateRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest,
                new Resource.Document(tenant, projectId, request.getPath()), Action.CREATE);
        String username = (String) httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        String mimeType = request.getMimeType() == null || request.getMimeType().isBlank()
                ? "text/markdown"
                : request.getMimeType();

        DocumentDocument created;
        try {
            byte[] bytes = request.getInlineText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            created = documentService.create(
                    tenant,
                    projectId,
                    request.getPath(),
                    request.getTitle(),
                    request.getTags(),
                    mimeType,
                    new java.io.ByteArrayInputStream(bytes),
                    username);
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    /**
     * Upload a binary or text file as a new document. The {@link DocumentService}
     * decides on its own whether the content fits inline (≤ inline-threshold,
     * text mime-type) or goes to {@code StorageService}. Caller passes the
     * file part alongside optional metadata; if {@code path} is missing it
     * defaults to the upload's original filename.
     */
    @PostMapping(value = "/brain/{tenant}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentDto> upload(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", required = false) @Nullable String path,
            @RequestParam(value = "title", required = false) @Nullable String title,
            @RequestParam(value = "tags", required = false) @Nullable String tagsCsv,
            @RequestParam(value = "mimeType", required = false) @Nullable String mimeType,
            HttpServletRequest httpRequest) {

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty.");
        }

        String username = (String) httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        String resolvedPath = path == null || path.isBlank() ? file.getOriginalFilename() : path;
        if (resolvedPath == null || resolvedPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot infer document path — neither `path` nor an upload filename was provided.");
        }
        authority.enforce(httpRequest,
                new Resource.Document(tenant, projectId, resolvedPath), Action.CREATE);
        String resolvedMime = mimeType == null || mimeType.isBlank() ? file.getContentType() : mimeType;
        List<String> tags = parseTagsCsv(tagsCsv);

        DocumentDocument created;
        try {
            created = documentService.create(
                    tenant,
                    projectId,
                    resolvedPath,
                    title == null || title.isBlank() ? null : title,
                    tags,
                    resolvedMime,
                    file.getInputStream(),
                    username);
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            log.warn("Upload failed for tenant='{}' project='{}' path='{}'", tenant, projectId, resolvedPath, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read upload stream.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    private static @Nullable List<String> parseTagsCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) return null;
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Streams the document content. Used by the web UI for image
     * previews, PDF rendering and downloads. Auth runs through the
     * normal {@code BrainAccessFilter}; the filter additionally
     * accepts {@code ?token=<jwt>} for this route so that
     * {@code <img src>} / {@code <a href>} tags work without a
     * custom {@code fetch+blob} dance — see
     * {@code BrainAccessFilter#allowsQueryToken}.
     *
     * @param download {@code true} → emits {@code Content-Disposition:
     *                 attachment} so browsers prompt a save dialog.
     *                 {@code false} (default) → inline render.
     */
    @GetMapping("/brain/{tenant}/documents/{id}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @RequestParam(value = "download", defaultValue = "false") boolean download,
            HttpServletRequest httpRequest) {
        DocumentDocument doc = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(doc.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        authority.enforce(httpRequest,
                new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.READ);
        InputStream stream = documentService.loadContent(doc);
        MediaType contentType = parseMimeType(doc.getMimeType());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        if (doc.getSize() > 0) {
            headers.setContentLength(doc.getSize());
        }
        String filename = doc.getName() == null || doc.getName().isBlank()
                ? "document" : doc.getName();
        String dispositionType = download ? "attachment" : "inline";
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                dispositionType + "; filename*=UTF-8''" + urlEncode(filename));
        // Cache hint — content for a given document id is immutable
        // (storage-backed), and inline-text changes via PUT bump the
        // server-side version anyway. Aggressive cache is OK for
        // image / PDF re-renders within a session.
        headers.setCacheControl("private, max-age=300");
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(stream));
    }

    private static MediaType parseMimeType(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(raw);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @PutMapping("/brain/{tenant}/documents/{id}")
    public ResponseEntity<DocumentDto> update(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody DocumentUpdateRequest request,
            HttpServletRequest httpRequest) {

        DocumentDocument existing = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(existing.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        authority.enforce(httpRequest,
                new Resource.Document(tenant, existing.getProjectId(), existing.getPath()), Action.WRITE);

        // RAG tri-state is applied as a separate atomic update so we can
        // distinguish "set to null" (auto) from "leave untouched" — the
        // overloaded update(...) method only carries a nullable Boolean
        // which collapses those two cases.
        applyRagEnabledOverride(id, request.getRagEnabled());

        DocumentDocument updated;
        try {
            updated = documentService.update(
                    id,
                    request.getTitle(),
                    request.getTags(),
                    request.getInlineText(),
                    request.getNewPath(),
                    request.getAutoSummary(),
                    request.getSummaryDirty(),
                    /* ragEnabled handled atomically above */ null,
                    request.getMimeType());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.ok(toDto(updated));
    }

    /**
     * Two-stage delete: documents that live outside the project's
     * trash folder get soft-deleted (moved to {@code _bin/}
     * via {@link DocumentService#trash}); documents already in the
     * trash get hard-deleted (row + storage blob gone). The behaviour
     * mirrors a desktop file manager — first DELETE moves to bin,
     * second DELETE on the bin entry is permanent.
     *
     * <p>Idempotent against unknown ids — returns 404 the second time.
     * Tenant cross-check identical to {@code findOne} / {@code update}:
     * the JWT's tenant must match the URL tenant must match the
     * document's tenant.
     */
    /**
     * Set / clear the document's summary. Used by the document
     * editor's caption field — single-field write keeps the payload
     * minimal compared to the larger {@code DocumentUpdateRequest}
     * path. Returns the updated DTO so the editor can refresh in
     * place.
     */
    @PutMapping("/brain/{tenant}/documents/{id}/summary")
    public DocumentDto setSummary(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody DocumentSummaryRequest request,
            HttpServletRequest httpRequest) {

        DocumentDocument existing = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(existing.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        authority.enforce(httpRequest,
                new Resource.Document(tenant, existing.getProjectId(), existing.getPath()),
                Action.WRITE);
        documentService.setSummary(id, request.getSummary());
        DocumentDocument refreshed = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return toDto(refreshed);
    }

    @DeleteMapping("/brain/{tenant}/documents/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        DocumentDocument existing = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(existing.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        authority.enforce(httpRequest,
                new Resource.Document(tenant, existing.getProjectId(), existing.getPath()), Action.DELETE);
        if (DocumentService.isTrash(existing.getPath())) {
            documentService.delete(id);
        } else {
            documentService.trash(id);
        }
        return ResponseEntity.noContent().build();
    }

    // ──────────────────── Archive endpoints ────────────────────

    /**
     * List archived versions of {@code id}, newest first. The body also
     * carries {@code totalCount} so the UI can render the version-count
     * badge without scanning {@code items}.
     */
    @GetMapping("/brain/{tenant}/documents/{id}/archives")
    public DocumentArchiveListResponse listArchives(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        DocumentDocument doc = loadDocumentForTenant(tenant, id);
        authority.enforce(httpRequest,
                new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.READ);
        List<DocumentArchiveDocument> archives = documentService.listArchives(doc);
        List<DocumentArchiveSummary> items = archives.stream()
                .map(DocumentController::toArchiveSummary)
                .toList();
        return DocumentArchiveListResponse.builder()
                .totalCount(items.size())
                .items(items)
                .build();
    }

    /**
     * Read one archived version including its inline snapshot text (for
     * inline-stored versions). Storage-backed versions return
     * {@code inlineText == null} — the UI then streams the body via
     * {@link #archiveContent}.
     */
    @GetMapping("/brain/{tenant}/documents/{id}/archives/{archiveId}")
    public DocumentArchiveDto findArchive(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @PathVariable("archiveId") String archiveId,
            HttpServletRequest httpRequest) {
        DocumentDocument doc = loadDocumentForTenant(tenant, id);
        authority.enforce(httpRequest,
                new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.READ);
        DocumentArchiveDocument archive = loadArchiveForLineage(doc, archiveId);
        return toArchiveDto(archive, documentService.readArchiveContent(archive));
    }

    /**
     * Stream the body of one archived version — mirrors {@link #content}
     * for live documents so the web UI can render previews / downloads
     * of historical versions consistently.
     */
    @GetMapping("/brain/{tenant}/documents/{id}/archives/{archiveId}/content")
    public ResponseEntity<InputStreamResource> archiveContent(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @PathVariable("archiveId") String archiveId,
            @RequestParam(value = "download", defaultValue = "false") boolean download,
            HttpServletRequest httpRequest) {
        DocumentDocument doc = loadDocumentForTenant(tenant, id);
        authority.enforce(httpRequest,
                new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.READ);
        DocumentArchiveDocument archive = loadArchiveForLineage(doc, archiveId);
        InputStream stream = documentService.loadArchiveContent(archive);
        MediaType contentType = parseMimeType(archive.getMimeType());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        if (archive.getSize() > 0) headers.setContentLength(archive.getSize());
        String filename = archive.getName() == null || archive.getName().isBlank()
                ? "document" : archive.getName();
        String dispositionType = download ? "attachment" : "inline";
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                dispositionType + "; filename*=UTF-8''" + urlEncode(filename));
        headers.setCacheControl("private, max-age=300");
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(stream));
    }

    /**
     * Restore an archived version into the live document. The current
     * live content is itself archived first (so the restore appears as
     * a new version event in the list), then overwritten with a copy
     * of the chosen archive's body.
     */
    @PostMapping("/brain/{tenant}/documents/{id}/archives/{archiveId}/restore")
    public DocumentDto restoreArchive(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @PathVariable("archiveId") String archiveId,
            HttpServletRequest httpRequest) {
        DocumentDocument doc = loadDocumentForTenant(tenant, id);
        authority.enforce(httpRequest,
                new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.WRITE);
        // Lineage check (also performed by DocumentService.restoreArchive)
        // — fail fast with 404 if the archive id does not belong to this doc.
        loadArchiveForLineage(doc, archiveId);
        DocumentDocument restored;
        try {
            restored = documentService.restoreArchive(id, archiveId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return toDto(restored);
    }

    /**
     * Delete one archived version permanently. The live document is
     * untouched. Idempotent against unknown archive ids — returns 404
     * the second time.
     */
    @DeleteMapping("/brain/{tenant}/documents/{id}/archives/{archiveId}")
    public ResponseEntity<Void> deleteArchive(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @PathVariable("archiveId") String archiveId,
            HttpServletRequest httpRequest) {
        DocumentDocument doc = loadDocumentForTenant(tenant, id);
        authority.enforce(httpRequest,
                new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.DELETE);
        loadArchiveForLineage(doc, archiveId);
        documentService.deleteArchive(archiveId);
        return ResponseEntity.noContent().build();
    }

    private DocumentDocument loadDocumentForTenant(String tenant, String id) {
        DocumentDocument doc = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(doc.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return doc;
    }

    private DocumentArchiveDocument loadArchiveForLineage(DocumentDocument doc, String archiveId) {
        DocumentArchiveDocument archive = documentService.findArchive(archiveId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!archive.getLineageId().equals(doc.getLineageId())
                || !archive.getTenantId().equals(doc.getTenantId())) {
            // Treat lineage mismatch as 404 — the archive exists, but not
            // for the document the caller addressed. Avoids leaking
            // cross-lineage existence through a 400/403.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return archive;
    }

    private static DocumentArchiveSummary toArchiveSummary(DocumentArchiveDocument archive) {
        return DocumentArchiveSummary.builder()
                .id(archive.getId())
                .lineageId(archive.getLineageId())
                .path(archive.getPath())
                .name(archive.getName())
                .title(archive.getTitle())
                .mimeType(archive.getMimeType())
                .size(archive.getSize())
                .tags(archive.getTags())
                .inline(archive.getInlineText() != null)
                .kind(archive.getKind())
                .createdBy(archive.getCreatedBy())
                .archivedAtMs(archive.getArchivedAt() == null
                        ? 0L
                        : archive.getArchivedAt().toEpochMilli())
                .build();
    }

    private static DocumentArchiveDto toArchiveDto(
            DocumentArchiveDocument archive, @Nullable String inlineBody) {
        boolean inline = archive.getInlineText() != null;
        return DocumentArchiveDto.builder()
                .id(archive.getId())
                .lineageId(archive.getLineageId())
                .path(archive.getPath())
                .name(archive.getName())
                .title(archive.getTitle())
                .mimeType(archive.getMimeType())
                .size(archive.getSize())
                .tags(archive.getTags())
                .inline(inline)
                .inlineText(inline ? inlineBody : null)
                .kind(archive.getKind())
                .createdBy(archive.getCreatedBy())
                .archivedAtMs(archive.getArchivedAt() == null
                        ? 0L
                        : archive.getArchivedAt().toEpochMilli())
                .build();
    }

    private static DocumentSummary toSummary(DocumentDocument doc) {
        return DocumentSummary.builder()
                .id(doc.getId())
                .projectId(doc.getProjectId())
                .path(doc.getPath())
                .name(doc.getName())
                .title(doc.getTitle())
                .mimeType(doc.getMimeType())
                .size(doc.getSize())
                .tags(doc.getTags())
                .createdAtMs(toEpochMillis(doc.getCreatedAt()))
                .createdBy(doc.getCreatedBy())
                .inline(doc.getInlineText() != null)
                .kind(doc.getKind())
                .build();
    }

    private static DocumentDto toDto(DocumentDocument doc) {
        return DocumentDto.builder()
                .id(doc.getId())
                .projectId(doc.getProjectId())
                .path(doc.getPath())
                .name(doc.getName())
                .title(doc.getTitle())
                .mimeType(doc.getMimeType())
                .size(doc.getSize())
                .tags(doc.getTags())
                .createdAtMs(toEpochMillis(doc.getCreatedAt()))
                .createdBy(doc.getCreatedBy())
                .inline(doc.getInlineText() != null)
                .inlineText(doc.getInlineText())
                .kind(doc.getKind())
                .headers(doc.getHeaders() == null
                        ? new java.util.LinkedHashMap<>()
                        : new java.util.LinkedHashMap<>(doc.getHeaders()))
                .autoSummary(doc.isAutoSummary())
                .summaryDirty(doc.isSummaryDirty())
                .summary(doc.getSummary())
                .summarizedAtMs(toEpochMillis(doc.getSummarizedAt()))
                .ragEnabled(doc.getRagEnabled())
                .build();
    }

    /**
     * Apply the tri-state {@code ragEnabled} override from the request.
     * Translates {@code "auto"} / {@code "on"} / {@code "off"} into the
     * persisted {@code null} / {@code true} / {@code false}. Absent or
     * blank input is a no-op. Anything else throws {@code 400}.
     */
    private void applyRagEnabledOverride(String docId, @Nullable String raw) {
        if (raw == null) return;
        String value = raw.trim().toLowerCase();
        if (value.isEmpty()) return;
        Boolean target;
        switch (value) {
            case "auto" -> target = null;
            case "on", "true" -> target = Boolean.TRUE;
            case "off", "false" -> target = Boolean.FALSE;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ragEnabled must be one of: auto, on, off");
        }
        documentService.setRagEnabledOverride(docId, target);
    }

    private static @Nullable Long toEpochMillis(@Nullable Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
