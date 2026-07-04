package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the canvas addon under
 * {@code /brain/{tenant}/addon/canvas/...}. Convenience for the Web-UI;
 * the LLM path is the {@code canvas_* / canvasbook_*} tools.
 *
 * <p>The graph endpoints keep YAML parsing on the server (one codec):
 * {@code GET /graph} returns the parsed {@link CanvasGraphDto},
 * {@code PUT /graph} serialises it back through {@link CanvasService}.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class CanvasbookAppController {

    private final CanvasbookApplication application;
    private final CanvasbookFolderReader folderReader;
    private final CanvasService canvasService;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final RequestAuthority authority;

    // ── Canvasbook (app) ──────────────────────────────────────────

    @GetMapping("/brain/{tenant}/addon/canvas/scan")
    public CanvasbookView scan(@PathVariable String tenant,
                               @RequestParam String projectId,
                               @RequestParam String folder,
                               HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        CanvasbookFolderReader.Scan scan = folderReader.scan(tenant, projectId, folder);

        List<CanvasbookPageView> pages = new ArrayList<>();
        for (CanvasbookFolderReader.Page p : scan.pages()) {
            pages.add(new CanvasbookPageView(
                    p.doc().getId(), p.doc().getPath(), p.relativePath(),
                    p.title(), p.description()));
        }

        String landingPath = null;
        String landingId = null;
        if (scan.landingPage() != null) {
            landingPath = scan.folder() + "/" + scan.landingPage();
            Optional<DocumentDocument> lp =
                    documentService.findByPath(tenant, projectId, landingPath);
            if (lp.isPresent()) landingId = lp.get().getId();
        }

        return new CanvasbookView(scan.folder(), scan.config().title(),
                scan.config().description(), landingPath, landingId, pages);
    }

    @PostMapping("/brain/{tenant}/addon/canvas/page")
    public CanvasbookPageView createPage(@PathVariable String tenant,
                                         @RequestParam String projectId,
                                         @RequestParam String folder,
                                         @RequestBody CanvasbookCreatePageRequest req,
                                         HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.CREATE);
        String normalised = CanvasbookFolderReader.normaliseFolder(folder);
        String title = req.title();
        String slug = req.slug() != null && !req.slug().isBlank()
                ? req.slug().trim()
                : CanvasbookApplication.slugify(title != null ? title : "canvas");

        DocumentDocument stored = canvasService.create(
                tenant, projectId, normalised + "/" + slug, title, req.description(),
                currentUser(request));
        application.refresh(new VanceApplication.RefreshContext(
                tenant, projectId, normalised, currentUser(request), null));

        String rel = stored.getPath().startsWith(normalised + "/")
                ? stored.getPath().substring(normalised.length() + 1) : stored.getPath();
        return new CanvasbookPageView(stored.getId(), stored.getPath(), rel,
                title != null ? title : slug, req.description());
    }

    @PostMapping("/brain/{tenant}/addon/canvas/rebuild")
    public CanvasbookRebuildResponse rebuild(@PathVariable String tenant,
                                             @RequestParam String projectId,
                                             @RequestParam String folder,
                                             HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        VanceApplication.RefreshResult result = application.refresh(
                new VanceApplication.RefreshContext(
                        tenant, projectId, folder, currentUser(request), null));
        VanceApplication.ArtefactResult index = result.artefacts().isEmpty()
                ? null : result.artefacts().get(0);
        int pageCount = index != null
                ? ((Number) index.stats().getOrDefault("pageCount", 0)).intValue() : 0;
        return new CanvasbookRebuildResponse(result.folder(),
                index != null ? index.path() : "",
                index != null ? index.markdownLink() : null, pageCount);
    }

    // ── Canvas page graph (kind: canvas) ──────────────────────────

    @GetMapping("/brain/{tenant}/addon/canvas/graph")
    public CanvasGraphDto getGraph(@PathVariable String tenant,
                                   @RequestParam String projectId,
                                   @RequestParam String path,
                                   HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        DocumentDocument doc = requireCanvas(tenant, projectId, path);
        return CanvasDtoMapper.toDto(canvasService.readDocument(doc));
    }

    @PutMapping("/brain/{tenant}/addon/canvas/graph")
    public CanvasGraphDto putGraph(@PathVariable String tenant,
                                   @RequestParam String projectId,
                                   @RequestParam String path,
                                   @RequestBody CanvasGraphDto body,
                                   HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        DocumentDocument doc = requireCanvas(tenant, projectId, path);
        DocumentDocument saved = canvasService.writeDocument(doc, CanvasDtoMapper.fromDto(body));
        return CanvasDtoMapper.toDto(canvasService.readDocument(saved));
    }

    // ── Helpers ───────────────────────────────────────────────────

    private DocumentDocument requireCanvas(String tenant, String projectId, String path) {
        DocumentDocument doc = documentService.findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ToolException("No canvas at '" + path + "'."));
        if (!CanvasService.KIND.equals(doc.getKind())) {
            throw new ToolException("Document '" + path + "' is not a canvas (kind="
                    + doc.getKind() + ").");
        }
        return doc;
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        Object o = req.getAttribute("vanceUserId");
        return o instanceof String s ? s : null;
    }
}
