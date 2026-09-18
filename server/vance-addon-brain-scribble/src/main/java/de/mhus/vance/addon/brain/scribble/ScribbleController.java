package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
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
 * REST surface for the scribble addon under
 * {@code /brain/{tenant}/addon/scribble/...}. Convenience for the Web-UI
 * editor; the LLM path is {@code document_read} plus the later
 * {@code scribble_*} tools.
 *
 * <p>The sheet endpoints keep YAML parsing on the server (one codec):
 * {@code GET /sheet} returns the parsed {@link ScribbleSheetView},
 * {@code PUT /sheet} serialises it back through {@link ScribbleService}.
 * The scribblebook container endpoints ({@code scan}/{@code page}/
 * {@code rebuild}) join with the app PR.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ScribbleController {

    private final ScribbleService scribbleService;
    private final ScribblebookApplication application;
    private final ScribblebookFolderReader folderReader;
    private final DocumentService documentService;
    private final RequestAuthority authority;
    private final ScribbleOcrService ocrService;
    private final ScribblePdfService pdfService;

    // ── Sheet (kind: scribble) ────────────────────────────────────

    @PostMapping("/brain/{tenant}/addon/scribble/sheet")
    public ScribbleSheetView createSheet(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestBody ScribbleCreateSheetRequest req,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.CREATE);
        if (req.path() == null || req.path().isBlank()) {
            throw new ToolException("A `path` is required to create a scribble.");
        }
        DocumentDocument stored =
                scribbleService.create(tenant, projectId, req.path(), req.title(), currentUser(request));
        return view(stored);
    }

    @GetMapping("/brain/{tenant}/addon/scribble/sheet")
    public ScribbleSheetView getSheet(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return view(requireScribble(tenant, projectId, path));
    }

    @PutMapping("/brain/{tenant}/addon/scribble/sheet")
    public ScribbleSheetView putSheet(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            @RequestBody ScribbleSheetDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        DocumentDocument doc = requireScribble(tenant, projectId, path);
        DocumentDocument saved = scribbleService.writeSheet(doc, ScribbleDtoMapper.fromDto(body), currentUser(request));
        return view(saved);
    }

    // ── Scribblebook (app) ────────────────────────────────────────

    @GetMapping("/brain/{tenant}/addon/scribble/scan")
    public ScribblebookView scan(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String folder,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        ScribblebookFolderReader.Scan scan = folderReader.scan(tenant, projectId, folder);

        List<ScribblebookPageView> pages = new ArrayList<>();
        for (ScribblebookFolderReader.Page p : scan.pages()) {
            pages.add(new ScribblebookPageView(
                    p.doc().getId(),
                    p.doc().getPath(),
                    p.relativePath(),
                    p.title(),
                    null,
                    p.enabled(),
                    p.defaultSheet()));
        }

        String landingPath = null;
        String landingId = null;
        if (scan.landingPage() != null) {
            landingPath = scan.folder() + "/" + scan.landingPage();
            Optional<DocumentDocument> lp = documentService.findByPath(tenant, projectId, landingPath);
            if (lp.isPresent()) landingId = lp.get().getId();
        }

        return new ScribblebookView(
                scan.folder(), scan.config().title(), scan.config().description(), landingPath, landingId, pages);
    }

    @PostMapping("/brain/{tenant}/addon/scribble/page")
    public ScribblebookPageView createPage(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String folder,
            @RequestBody ScribblebookCreatePageRequest req,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.CREATE);
        String normalised = ScribblebookFolderReader.normaliseFolder(folder);
        String title = req.title();
        String slug = req.slug() != null && !req.slug().isBlank()
                ? req.slug().trim()
                : ScribblebookApplication.slugify(title != null ? title : "sheet");

        DocumentDocument stored =
                scribbleService.create(tenant, projectId, normalised + "/" + slug, title, currentUser(request));
        application.refresh(
                new VanceApplication.RefreshContext(tenant, projectId, normalised, currentUser(request), null));

        String rel = stored.getPath().startsWith(normalised + "/")
                ? stored.getPath().substring(normalised.length() + 1)
                : stored.getPath();
        return new ScribblebookPageView(
                stored.getId(), stored.getPath(), rel, title != null ? title : slug, null, true, false);
    }

    @PostMapping("/brain/{tenant}/addon/scribble/rebuild")
    public ScribblebookRebuildResponse rebuild(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String folder,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        VanceApplication.RefreshResult result = application.refresh(
                new VanceApplication.RefreshContext(tenant, projectId, folder, currentUser(request), null));
        VanceApplication.ArtefactResult index =
                result.artefacts().isEmpty() ? null : result.artefacts().get(0);
        int pageCount = index != null ? ((Number) index.stats().getOrDefault("pageCount", 0)).intValue() : 0;
        return new ScribblebookRebuildResponse(
                result.folder(),
                index != null ? index.path() : "",
                index != null ? index.markdownLink() : null,
                pageCount);
    }

    // ── OCR + PDF export ─────────────────────────────────────────

    @PostMapping("/brain/{tenant}/addon/scribble/ocr")
    public ScribbleOcrResponse ocr(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        DocumentDocument doc = requireScribble(tenant, projectId, path);
        ScribbleOcrService.OcrResult result = ocrService.transcribe(tenant, projectId, doc, currentUser(request));
        return new ScribbleOcrResponse(result.mdPath(), result.markdown().length());
    }

    @PostMapping("/brain/{tenant}/addon/scribble/pdf")
    public ScribblePdfResponse sheetPdf(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        DocumentDocument doc = requireScribble(tenant, projectId, path);
        ScribblePdfService.SheetExport export = pdfService.exportSheet(tenant, projectId, doc, currentUser(request));
        return new ScribblePdfResponse(export.pdfPath(), 1, List.of());
    }

    @PostMapping("/brain/{tenant}/addon/scribble/bookpdf")
    public ScribblePdfResponse bookPdf(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String folder,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        ScribblePdfService.BookExport export = pdfService.exportBook(tenant, projectId, folder, currentUser(request));
        return new ScribblePdfResponse(export.pdfPath(), export.pageCount(), export.skipped());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private ScribbleSheetView view(DocumentDocument doc) {
        return new ScribbleSheetView(
                doc.getId(), doc.getPath(), doc.getTitle(), ScribbleDtoMapper.toDto(scribbleService.readSheet(doc)));
    }

    private DocumentDocument requireScribble(String tenant, String projectId, String path) {
        DocumentDocument doc = documentService
                .findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ToolException("No scribble at '" + path + "'."));
        if (!ScribbleService.KIND.equals(doc.getKind())) {
            throw new ToolException("Document '" + path + "' is not a scribble (kind=" + doc.getKind() + ").");
        }
        return doc;
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        // One spelling for "who is doing this". Reading the attribute by
        // hand is what put the wrong name here: nothing ever set
        // "vanceUserId", so every actor recorded from this request was null.
        return AccessFilterBase.usernameOrNull(req);
    }
}
