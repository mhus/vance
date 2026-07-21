package de.mhus.vance.addon.brain.kanban;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplication.ArtefactResult;
import de.mhus.vance.brain.applications.VanceApplication.CreateContext;
import de.mhus.vance.brain.applications.VanceApplication.CreateResult;
import de.mhus.vance.brain.applications.VanceApplication.RefreshContext;
import de.mhus.vance.brain.applications.VanceApplication.RefreshResult;

import de.mhus.vance.api.common.AccentColor;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.CardCodec;
import de.mhus.vance.shared.document.kind.CardDocument;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for the interactive Kanban board editor in the
 * Web-UI. Thin adapter over {@link KanbanApplication} +
 * {@link DocumentService} — no business logic here.
 *
 * <p>Authority is enforced per request via {@link RequestAuthority}
 * exactly like {@code DocumentController}: project-level READ for
 * the board view, document-level WRITE/CREATE/DELETE for mutations.
 *
 * <p>Card paths come in as query parameters (cards have slashes in
 * their paths; query params are cleaner than encoding into the URL
 * path segment).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class KanbanBoardController {

    private static final String MD_MIME = "text/markdown";
    private static final String CARD_KIND = "card";
    /** Re-read + re-merge attempts before surfacing an optimistic-lock conflict. */
    private static final int UPDATE_MAX_ATTEMPTS = 3;

    private final KanbanApplication kanbanApplication;
    private final KanbanFolderReader folderReader;
    private final DocumentService documentService;
    private final RequestAuthority authority;

    // ── Board view ────────────────────────────────────────────────

    @GetMapping("/brain/{tenant}/addon/kanban/board")
    public KanbanBoardView getBoard(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = normaliseFolder(folder);
        KanbanFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);
        return KanbanBoardMapper.toView(scan, List.of());
    }

    // ── Move ──────────────────────────────────────────────────────

    @PostMapping("/brain/{tenant}/addon/kanban/move")
    public KanbanMoveResponse move(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @Valid @RequestBody KanbanMoveRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = normaliseFolder(folder);
        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                tenant, projectId, normalised, currentUser(httpRequest), null);

        KanbanApplication.MoveResult mv = kanbanApplication.moveCard(
                rc, normalised, request.getCard(), request.getToColumn());

        return KanbanMoveResponse.builder()
                .card(mv.cardPath())
                .fromColumn(mv.fromColumn())
                .toColumn(mv.toColumn())
                .warnings(new ArrayList<>(mv.warnings()))
                .build();
    }

    // ── Card create ───────────────────────────────────────────────

    @PostMapping("/brain/{tenant}/addon/kanban/cards")
    public KanbanCardView createCard(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @Valid @RequestBody KanbanCardCreateRequest request,
            HttpServletRequest httpRequest) {

        String normalisedFolder = normaliseFolder(folder);
        String column = request.getColumn() != null && !request.getColumn().isBlank()
                ? sanitiseName(request.getColumn())
                : KanbanFolderReader.DEFAULT_COLUMN;

        String slug = request.getFilename() != null && !request.getFilename().isBlank()
                ? sanitiseName(request.getFilename())
                : sanitiseName(request.getTitle());
        String path = normalisedFolder + "/" + column + "/" + slug + ".md";

        authority.enforce(httpRequest,
                new Resource.Document(tenant, projectId, path), Action.CREATE);

        if (documentService.findByPath(tenant, projectId, path).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Card already exists at '" + path + "'.");
        }

        CardDocument card = new CardDocument(
                CARD_KIND, request.getTitle(),
                request.getPriority(), request.getAssignee(),
                request.getLabels() != null ? new ArrayList<>(request.getLabels()) : new ArrayList<>(),
                request.getDueDate(), request.getEstimate(),
                request.isBlocked(),
                request.getBody() != null ? request.getBody() : "",
                new LinkedHashMap<>());
        String body = CardCodec.serialize(card, MD_MIME);
        DocumentDocument stored = writeNew(tenant, projectId, path, request.getTitle(), body, httpRequest);

        log.info("KanbanBoardController.createCard tenant='{}' folder='{}' path='{}'",
                tenant, normalisedFolder, stored.getPath());

        return toCardView(stored, column, card);
    }

    // ── Card update ───────────────────────────────────────────────

    @PatchMapping("/brain/{tenant}/addon/kanban/cards")
    public KanbanCardView updateCard(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("path") String path,
            @Valid @RequestBody KanbanCardUpdateRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest,
                new Resource.Document(tenant, projectId, path), Action.WRITE);

        DocumentDocument doc0 = documentService.findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Card not found: " + path));
        if (!CARD_KIND.equalsIgnoreCase(doc0.getKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Document at '" + path + "' is not a card (kind="
                            + doc0.getKind() + ").");
        }

        // Accent color is a document-level field, not part of the card
        // front-matter. Apply it atomically (single-field $set/$unset) BEFORE
        // the content merge so the merge's versioned save preserves it.
        if (Boolean.TRUE.equals(request.getClearColor())) {
            documentService.clearColor(doc0.getId());
        } else if (request.getColor() != null) {
            AccentColor accent;
            try {
                accent = AccentColor.valueOf(request.getColor());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown accent color '" + request.getColor() + "'.");
            }
            documentService.setColor(doc0.getId(), accent);
        }

        DocumentDocument updated;
        CardDocument merged;
        if (hasCardFieldChange(request)) {
            // Read-modify-write under optimistic locking: another writer (a
            // concurrent LLM turn, a sibling browser) may bump the document
            // version between our read and save. Re-read + re-merge the patch
            // on conflict rather than surfacing a 500 — the patch carries only
            // the fields the caller changed, so re-applying on the latest card
            // is the correct resolution.
            DocumentDocument saved = null;
            CardDocument mergedCard = null;
            for (int attempt = 1; ; attempt++) {
                DocumentDocument doc = documentService.findByPath(tenant, projectId, path)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Card not found: " + path));
                CardDocument existing;
                try {
                    existing = CardCodec.parse(loadAsText(doc), doc.getMimeType());
                } catch (Exception e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Could not parse card '" + path + "': " + e.getMessage());
                }
                mergedCard = mergePatch(existing, request);
                String mergedBody = CardCodec.serialize(mergedCard, MD_MIME);
                try {
                    saved = documentService.update(
                            doc.getId(), mergedCard.title(), List.of(CARD_KIND),
                            mergedBody, null, null, null, null, MD_MIME);
                    break;
                } catch (OptimisticLockingFailureException e) {
                    if (attempt >= UPDATE_MAX_ATTEMPTS) {
                        throw e;
                    }
                    log.trace("KanbanBoardController.updateCard optimistic-lock retry {} for '{}'",
                            attempt, path);
                }
            }
            updated = saved;
            merged = mergedCard;
        } else {
            // Color-only patch: nothing to merge. Re-read the (now recolored)
            // document and parse it for the response view.
            updated = documentService.findByPath(tenant, projectId, path)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Card not found: " + path));
            try {
                merged = CardCodec.parse(loadAsText(updated), updated.getMimeType());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Could not parse card '" + path + "': " + e.getMessage());
            }
        }

        log.info("KanbanBoardController.updateCard tenant='{}' folder='{}' path='{}'",
                tenant, folder, updated.getPath());

        String column = KanbanFolderReader.columnFor(normaliseFolder(folder), updated.getPath());
        return toCardView(updated, column, merged);
    }

    // ── Card delete ───────────────────────────────────────────────

    @DeleteMapping("/brain/{tenant}/addon/kanban/cards")
    public void deleteCard(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest,
                new Resource.Document(tenant, projectId, path), Action.DELETE);

        DocumentDocument doc = documentService.findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Card not found: " + path));
        if (!CARD_KIND.equalsIgnoreCase(doc.getKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Document at '" + path + "' is not a card.");
        }
        documentService.trash(doc.getId());
        log.info("KanbanBoardController.deleteCard tenant='{}' folder='{}' path='{}'",
                tenant, folder, path);
    }

    // ── Rebuild ───────────────────────────────────────────────────

    @PostMapping("/brain/{tenant}/addon/kanban/rebuild")
    public KanbanRebuildResponse rebuild(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = normaliseFolder(folder);
        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                tenant, projectId, normalised, currentUser(httpRequest), null);
        VanceApplication.RefreshResult result = kanbanApplication.refresh(rc);

        List<KanbanArtefactSummary> arts = new ArrayList<>();
        for (VanceApplication.ArtefactResult a : result.artefacts()) {
            arts.add(KanbanArtefactSummary.builder()
                    .name(a.name()).path(a.path()).markdownLink(a.markdownLink()).build());
        }
        return KanbanRebuildResponse.builder()
                .folder(normalised)
                .artefacts(arts)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** True when the patch touches any card front-matter / body field (color is document-level). */
    private static boolean hasCardFieldChange(KanbanCardUpdateRequest p) {
        return p.getTitle() != null
                || p.getPriority() != null
                || p.getAssignee() != null
                || p.getLabels() != null
                || p.getDueDate() != null
                || p.getEstimate() != null
                || p.getBlocked() != null
                || p.getBody() != null;
    }

    private static CardDocument mergePatch(CardDocument existing, KanbanCardUpdateRequest p) {
        String title = p.getTitle() != null ? p.getTitle() : existing.title();
        String priority = p.getPriority() != null
                ? (p.getPriority().isBlank() ? null : p.getPriority())
                : existing.priority();
        String assignee = p.getAssignee() != null
                ? (p.getAssignee().isBlank() ? null : p.getAssignee())
                : existing.assignee();
        List<String> labels = p.getLabels() != null
                ? new ArrayList<>(p.getLabels())
                : new ArrayList<>(existing.labels());
        String dueDate = p.getDueDate() != null
                ? (p.getDueDate().isBlank() ? null : p.getDueDate())
                : existing.dueDate();
        Double estimate = p.getEstimate() != null ? p.getEstimate() : existing.estimate();
        boolean blocked = p.getBlocked() != null ? p.getBlocked() : existing.blocked();
        String body = p.getBody() != null ? p.getBody() : existing.body();
        return new CardDocument(
                existing.kind(), title, priority, assignee, labels, dueDate,
                estimate, blocked, body, existing.extra());
    }

    private DocumentDocument writeNew(String tenant, String projectId,
                                      String path, String title, String body,
                                      HttpServletRequest httpRequest) {
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    tenant, projectId, path, title,
                    List.of(CARD_KIND), MD_MIME, in, currentUser(httpRequest));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not write card '" + path + "': " + e.getMessage());
        }
    }

    private String loadAsText(DocumentDocument doc) {
        if (documentService.readContent(doc) != null) return documentService.readContent(doc);
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read card body: " + e.getMessage());
        }
    }

    private static KanbanCardView toCardView(DocumentDocument doc, String column, CardDocument card) {
        int[] cb = CardCodec.countCheckboxes(card.body());
        return KanbanCardView.builder()
                .path(doc.getPath())
                .column(column)
                .title(card.title())
                .priority(card.priority())
                .assignee(card.assignee())
                .labels(new ArrayList<>(card.labels()))
                .dueDate(card.dueDate())
                .estimate(card.estimate())
                .blocked(card.blocked())
                .color(doc.getColor() != null ? doc.getColor().name() : null)
                .body(card.body().isEmpty() ? null : card.body())
                .subtaskTotal(cb[0])
                .subtaskDone(cb[1])
                .build();
    }

    private static String normaliseFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "folder must be provided");
        }
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "folder must not be empty");
        }
        return f;
    }

    private static String sanitiseName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.length() == 0 ? "card" : sb.toString();
    }

    private static @Nullable String currentUser(HttpServletRequest httpRequest) {
        Object v = httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        return v instanceof String s ? s : null;
    }
}
