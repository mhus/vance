package de.mhus.vance.brain.documents;

import de.mhus.vance.api.documents.DocumentCreateRequest;
import de.mhus.vance.api.documents.DocumentDto;
import de.mhus.vance.api.documents.DocumentListResponse;
import de.mhus.vance.api.documents.DocumentSummary;
import de.mhus.vance.api.documents.DocumentUpdateRequest;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/brain/{tenant}/documents")
    public DocumentListResponse list(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {

        Page<DocumentDocument> result = documentService.listByProjectPaged(tenant, projectId, page, size);
        return DocumentListResponse.builder()
                .items(result.getContent().stream().map(DocumentController::toSummary).toList())
                .page(result.getNumber())
                .pageSize(result.getSize())
                .totalCount(result.getTotalElements())
                .build();
    }

    @GetMapping("/brain/{tenant}/documents/{id}")
    public DocumentDto findOne(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id) {

        DocumentDocument doc = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(doc.getTenantId())) {
            // The filter ensures the JWT's tenant matches the path; this guards
            // against a caller fetching a document that lives in a different
            // tenant by guessing its id.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return toDto(doc);
    }

    @PostMapping("/brain/{tenant}/documents")
    public ResponseEntity<DocumentDto> create(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @Valid @RequestBody DocumentCreateRequest request,
            HttpServletRequest httpRequest) {

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

    @PutMapping("/brain/{tenant}/documents/{id}")
    public ResponseEntity<DocumentDto> update(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody DocumentUpdateRequest request) {

        DocumentDocument existing = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(existing.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        DocumentDocument updated;
        try {
            updated = documentService.update(
                    id,
                    request.getTitle(),
                    request.getTags(),
                    request.getInlineText());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.ok(toDto(updated));
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
                .build();
    }

    private static @Nullable Long toEpochMillis(@Nullable Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
