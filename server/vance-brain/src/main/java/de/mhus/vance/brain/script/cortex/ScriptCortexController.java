package de.mhus.vance.brain.script.cortex;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.api.documents.DocumentDto;
import de.mhus.vance.api.documents.DocumentListResponse;
import de.mhus.vance.api.documents.DocumentSummary;
import de.mhus.vance.api.scripts.ScriptCreateRequest;
import de.mhus.vance.api.scripts.ScriptDeepValidateResponse;
import de.mhus.vance.api.scripts.ScriptDeepWarning;
import de.mhus.vance.api.scripts.ScriptExecuteRequest;
import de.mhus.vance.api.scripts.ScriptExecuteResponse;
import de.mhus.vance.api.scripts.ScriptExecutionStatus;
import de.mhus.vance.api.scripts.ScriptGenerateRequest;
import de.mhus.vance.api.scripts.ScriptGenerateResponse;
import de.mhus.vance.api.scripts.ScriptGenerationResult;
import de.mhus.vance.api.scripts.ScriptValidateRequest;
import de.mhus.vance.api.scripts.ScriptValidateError;
import de.mhus.vance.api.scripts.ScriptValidateResponse;
import de.mhus.vance.brain.hactar.HactarEngine;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.script.JsValidationService;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * REST surface for the Script Cortex Web-UI editor.
 *
 * <p>All endpoints live under {@code /brain/{tenant}/scripts/...}. The
 * {@code tenant} path-segment is validated by
 * {@code BrainAccessFilter} against the JWT's {@code tid} claim; the
 * {@code projectId} query-parameter is the project's business name
 * (mirrors {@link de.mhus.vance.brain.documents.DocumentController}).
 *
 * <p>Document CRUD delegates to {@link DocumentService}; the controller
 * is a thin shim that
 *
 * <ol>
 *   <li>stamps {@code kind="script"} on creation,</li>
 *   <li>derives the mime-type from the path extension when absent,</li>
 *   <li>filters the list-endpoint to {@code kind=script} so the UI
 *       never sees other kinds.</li>
 * </ol>
 *
 * <p>Execute / validate / generate plumb through their dedicated
 * services. See {@code planning/script-cortex.md}.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ScriptCortexController {

    private final DocumentService documentService;
    private final JsValidationService jsValidationService;
    private final ScriptCortexDeepValidateService deepValidateService;
    private final ScriptCortexExecutionService executionService;
    private final ScriptCortexToolPolicy toolPolicy;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final RequestAuthority authority;
    private final ObjectMapper objectMapper;

    // ──────────────────── Document CRUD ────────────────────

    @GetMapping("/brain/{tenant}/scripts")
    public DocumentListResponse list(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "500") int size,
            @RequestParam(value = "pathPrefix", required = false) @Nullable String pathPrefix,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        // No kind-filter on purpose — Script Cortex is a file explorer
        // over the full project tree, not a "scripts only" silo. The
        // {@code .js} extension is what makes a file executable; other
        // kinds (manuals, data, notes) live alongside.
        Page<DocumentDocument> result = documentService.listByProjectPaged(
                tenant, projectId, page, size, pathPrefix, /*kind*/ null);
        return DocumentListResponse.builder()
                .items(result.getContent().stream().map(ScriptCortexController::toSummary).toList())
                .page(result.getNumber())
                .pageSize(result.getSize())
                .totalCount(result.getTotalElements())
                .build();
    }

    @GetMapping("/brain/{tenant}/scripts/{id}")
    public DocumentDto get(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        DocumentDocument doc = loadOwned(tenant, id);
        authority.enforce(httpRequest,
                new Resource.Document(tenant, doc.getProjectId(), doc.getPath()), Action.READ);
        return toDto(doc);
    }

    @PostMapping("/brain/{tenant}/scripts")
    public ResponseEntity<DocumentDto> create(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @Valid @RequestBody ScriptCreateRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest,
                new Resource.Document(tenant, projectId, request.getPath()), Action.CREATE);
        String username = (String) httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        String mimeType = ScriptMimeResolver.resolve(request.getPath(), request.getMimeType());
        String inlineText = request.getInlineText() == null ? "" : request.getInlineText();

        DocumentDocument created;
        try {
            created = documentService.create(
                    tenant,
                    projectId,
                    request.getPath(),
                    request.getTitle(),
                    request.getTags(),
                    mimeType,
                    new ByteArrayInputStream(inlineText.getBytes(StandardCharsets.UTF_8)),
                    username);
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        // No kind-stamping. Script Cortex doesn't need a special marker
        // — the file's extension is what makes it executable. Leaving
        // kind alone keeps the document fully editable from the
        // documents.html surface too.
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    @PutMapping("/brain/{tenant}/scripts/{id}")
    public DocumentDto update(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @RequestBody UpdateBody body,
            HttpServletRequest httpRequest) {

        DocumentDocument existing = loadOwned(tenant, id);
        authority.enforce(httpRequest,
                new Resource.Document(tenant, existing.getProjectId(), existing.getPath()), Action.WRITE);

        DocumentDocument updated;
        try {
            updated = documentService.update(
                    id,
                    body.title,
                    body.tags,
                    body.inlineText,
                    body.newPath,
                    /*autoSummary*/ null,
                    /*summaryDirty*/ null);
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return toDto(updated);
    }

    @DeleteMapping("/brain/{tenant}/scripts/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        DocumentDocument existing = loadOwned(tenant, id);
        authority.enforce(httpRequest,
                new Resource.Document(tenant, existing.getProjectId(), existing.getPath()), Action.DELETE);
        if (DocumentService.isTrash(existing.getPath())) {
            documentService.delete(id);
        } else {
            documentService.trash(id);
        }
        return ResponseEntity.noContent().build();
    }

    // ──────────────────── Validation ────────────────────

    @PostMapping("/brain/{tenant}/scripts/validate")
    public ScriptValidateResponse validateQuick(
            @PathVariable("tenant") String tenant,
            @RequestBody ScriptValidateRequest request) {
        String code = resolveCode(tenant, request);
        JsValidationService.JsValidationResult result =
                jsValidationService.validate(code, request.getSourceName());
        List<ScriptValidateError> errors = new ArrayList<>();
        for (JsValidationService.JsValidationError e : result.errors()) {
            errors.add(ScriptValidateError.builder()
                    .sourceName(e.sourceName())
                    .line(e.line())
                    .column(e.column())
                    .message(e.message())
                    .build());
        }
        return ScriptValidateResponse.builder()
                .ok(result.ok())
                .errors(errors)
                .build();
    }

    @PostMapping("/brain/{tenant}/scripts/validate-deep")
    public ScriptDeepValidateResponse validateDeep(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestBody ScriptValidateRequest request) {
        String code = resolveCode(tenant, request);
        @Nullable String resolvedProjectId = projectId;
        @Nullable String docId = request.getScriptId();
        if (docId != null && resolvedProjectId == null) {
            DocumentDocument doc = loadOwned(tenant, docId);
            resolvedProjectId = doc.getProjectId();
        }
        ScriptDeepValidateResponse resp = deepValidateService.review(
                tenant, resolvedProjectId, code, request.getSourceName());
        if (docId != null) {
            cacheDeepReview(docId, code, resp);
        }
        return resp;
    }

    // ──────────────────── Execute ────────────────────

    @PostMapping("/brain/{tenant}/scripts/execute")
    public ScriptExecuteResponse execute(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestBody ScriptExecuteRequest request,
            HttpServletRequest httpRequest) {

        String code;
        @Nullable String resolvedProjectId = projectId;
        @Nullable String sourceName = request.getSourceName();
        if (request.getScriptId() != null && !request.getScriptId().isBlank()) {
            DocumentDocument doc = loadOwned(tenant, request.getScriptId());
            authority.enforce(httpRequest,
                    new Resource.Document(tenant, doc.getProjectId(), doc.getPath()),
                    Action.EXECUTE);
            code = documentService.readContent(doc);
            if (resolvedProjectId == null) resolvedProjectId = doc.getProjectId();
            if (sourceName == null) sourceName = doc.getPath();
        } else if (request.getCode() != null && !request.getCode().isBlank()) {
            code = request.getCode();
            if (resolvedProjectId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "projectId is required when executing inline code");
            }
            authority.enforce(httpRequest,
                    new Resource.Project(tenant, resolvedProjectId), Action.EXECUTE);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Either scriptId or code must be supplied");
        }

        String username = (String) httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        ScriptCortexExecutionService.StartRequest startReq =
                new ScriptCortexExecutionService.StartRequest(
                        tenant,
                        resolvedProjectId,
                        username,
                        code,
                        sourceName,
                        request.getArgs(),
                        request.getTimeoutMs());
        String executionId = executionService.start(startReq);
        return ScriptExecuteResponse.builder().executionId(executionId).build();
    }

    @PostMapping("/brain/{tenant}/scripts/executions/{executionId}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable("tenant") String tenant,
            @PathVariable("executionId") String executionId) {
        boolean ok = executionService.cancel(executionId);
        return ok
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @GetMapping("/brain/{tenant}/scripts/executions/{executionId}")
    public ScriptExecutionStatus executionStatus(
            @PathVariable("tenant") String tenant,
            @PathVariable("executionId") String executionId) {
        return executionService.getStatus(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown executionId: " + executionId));
    }

    // ──────────────────── Hactar-Generation ────────────────────

    @PostMapping("/brain/{tenant}/scripts/generate")
    public ScriptGenerateResponse generate(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("sessionId") String sessionId,
            @Valid @RequestBody ScriptGenerateRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest,
                new Resource.Project(tenant, projectId), Action.CREATE);
        String goal = buildGoal(tenant, request);
        String processName = "script-gen-" + UUID.randomUUID().toString().substring(0, 8);

        // Symmetric tool surface — DT's drafting/framing prompt renders
        // toolInventory from scriptAllowedTools, and ScriptCortexExecutionService
        // applies the same list as the runtime allow-set. Without this
        // the LLM would either be told "no tools — pure JS only" (and
        // never reach for vance.tools.call) or, worse, be promised
        // tools the executor rejects at runtime.
        List<String> allowedTools = toolPolicy.availableTools(
                tenant, projectId, sessionId,
                (String) httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME));

        Map<String, Object> engineParams = new LinkedHashMap<>();
        engineParams.put(HactarEngine.GOAL_KEY, goal);
        engineParams.put(HactarEngine.EXECUTE_ON_DONE_KEY, Boolean.FALSE);
        engineParams.put(HactarEngine.MAX_RECOVERIES_KEY, 5);
        engineParams.put(HactarEngine.SCRIPT_ALLOWED_TOOLS_KEY, allowedTools);

        ThinkProcessDocument process = thinkProcessService.create(
                tenant,
                projectId,
                sessionId,
                processName,
                HactarEngine.NAME,
                HactarEngine.VERSION,
                "Script Cortex generation",
                goal,
                /*parentProcessId*/ null,
                engineParams,
                "hactar",
                /*promptOverride*/ null,
                /*promptMode*/ null,
                /*allowedToolsOverride*/ null);
        try {
            thinkEngineService.start(process);
        } catch (RuntimeException e) {
            log.warn("Failed to start Deep-Thought generation process='{}': {}",
                    process.getId(), e.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to start Deep-Thought: " + e.getMessage());
        }
        return ScriptGenerateResponse.builder()
                .thinkProcessId(process.getId())
                .processName(processName)
                .build();
    }

    @GetMapping("/brain/{tenant}/scripts/generations/{thinkProcessId}/result")
    public ScriptGenerationResult generationResult(
            @PathVariable("tenant") String tenant,
            @PathVariable("thinkProcessId") String thinkProcessId) {
        ThinkProcessDocument process = thinkProcessService.findById(thinkProcessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(process.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        HactarState state = loadState(process);
        return ScriptGenerationResult.builder()
                .thinkProcessId(thinkProcessId)
                .status(process.getStatus() == null ? null : process.getStatus().name())
                .reason(state.getStatus() == null ? null : state.getStatus().name())
                .code(state.getGeneratedCode())
                .reviewerNotes(state.getReviewerNotes())
                .planSketch(state.getPlanSketch())
                .failureReason(state.getFailureReason())
                .build();
    }

    // ──────────────────── Internals ────────────────────

    private DocumentDocument loadOwned(String tenant, String id) {
        DocumentDocument doc = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(doc.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return doc;
    }

    private String resolveCode(String tenant, ScriptValidateRequest request) {
        if (request.getCode() != null && !request.getCode().isBlank()) {
            return request.getCode();
        }
        if (request.getScriptId() != null && !request.getScriptId().isBlank()) {
            DocumentDocument doc = loadOwned(tenant, request.getScriptId());
            return documentService.readContent(doc);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Either scriptId or code must be supplied");
    }

    private String buildGoal(String tenant, ScriptGenerateRequest request) {
        String userPrompt = request.getPrompt();
        String existingId = request.getExistingScriptId();
        if (existingId == null || existingId.isBlank()) {
            return userPrompt;
        }
        DocumentDocument existing = loadOwned(tenant, existingId);
        String body = documentService.readContent(existing);
        StringBuilder sb = new StringBuilder(userPrompt.length() + body.length() + 200);
        sb.append(userPrompt);
        sb.append("\n\nExisting script to improve (file: `")
                .append(existing.getPath()).append("`):\n\n```js\n");
        sb.append(body);
        sb.append("\n```\n");
        return sb.toString();
    }

    private HactarState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return HactarState.builder().status(HactarStatus.READY).build();
        Object raw = p.get(HactarEngine.STATE_KEY);
        if (raw == null) return HactarState.builder().status(HactarStatus.READY).build();
        return objectMapper.convertValue(raw, HactarState.class);
    }

    private void cacheDeepReview(
            String docId, String code, ScriptDeepValidateResponse response) {
        String hash = sha256Hex(code);
        try {
            List<ScriptDeepWarning> warnings = response.getWarnings() == null
                    ? List.of() : response.getWarnings();
            String json = objectMapper.writeValueAsString(warnings);
            documentService.setDeepReviewCache(docId, hash, json);
        } catch (RuntimeException e) {
            log.warn("Failed to persist deep-review cache for doc='{}': {}",
                    docId, e.toString());
        }
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ──────────────────── DTO mapping ────────────────────

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
                .createdAtMs(toMillis(doc.getCreatedAt()))
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
                .createdAtMs(toMillis(doc.getCreatedAt()))
                .createdBy(doc.getCreatedBy())
                .inline(doc.getInlineText() != null)
                .inlineText(doc.getInlineText())
                .kind(doc.getKind())
                .headers(doc.getHeaders() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(doc.getHeaders()))
                .autoSummary(doc.isAutoSummary())
                .summaryDirty(doc.isSummaryDirty())
                .summary(doc.getSummary())
                .summarizedAtMs(toMillis(doc.getSummarizedAt()))
                .ragEnabled(doc.getRagEnabled())
                .lastDeepReviewedHash(doc.getLastDeepReviewedHash())
                .lastDeepReviewWarningsJson(doc.getLastDeepReviewWarningsJson())
                .lastDeepReviewedAtMs(toMillis(doc.getLastDeepReviewedAt()))
                .build();
    }

    private static @Nullable Long toMillis(@Nullable Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    /** Request body for {@link #update}. Kept as a public static class
     *  (rather than a record) so the field-presence semantics — null
     *  means "leave untouched" — translate cleanly through Jackson. */
    public static class UpdateBody {
        public @Nullable String title;
        public @Nullable List<String> tags;
        public @Nullable String inlineText;
        public @Nullable String newPath;
    }
}
