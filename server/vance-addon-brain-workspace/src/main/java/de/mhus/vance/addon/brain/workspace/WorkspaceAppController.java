package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.addon.brain.canvas.CanvasService;
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
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the interactive Workspace editor in the Web-UI.
 * Thin adapter over {@link WorkspaceApplication} +
 * {@link WorkspaceFolderReader} — no business logic here.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class WorkspaceAppController {

    private final WorkspaceApplication application;
    private final WorkspaceFolderReader folderReader;
    private final DocumentService documentService;
    private final CanvasService canvasService;
    private final DocumentLinkBuilder linkBuilder;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/workspace/scan")
    public WorkspaceView scan(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = WorkspaceFolderReader.normaliseFolder(folder);
        WorkspaceFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);

        List<WorkspacePageView> pages = new ArrayList<>();
        for (WorkspacePage p : scan.pages()) {
            pages.add(new WorkspacePageView(
                    p.doc().getId(), p.doc().getPath(), p.relativePath(), p.section(),
                    p.title(), p.description()));
        }

        String indexPath = WorkspaceFolderReader.resolveOutputPath(
                normalised, scan.config().index().outputPath());
        @Nullable String indexPageId = resolveId(tenant, projectId, indexPath);

        @Nullable String landingPagePath = null;
        @Nullable String landingPageId = null;
        if (scan.config().landingPage() != null && !scan.config().landingPage().isBlank()) {
            String lp = scan.config().landingPage();
            landingPagePath = lp.startsWith("/")
                    ? lp.substring(1)
                    : normalised + "/" + lp;
            landingPageId = resolveId(tenant, projectId, landingPagePath);
        }

        return new WorkspaceView(
                normalised,
                scan.manifest().getTitle(),
                null, // description is not directly on DocumentDocument; read from manifest body if needed
                landingPagePath,
                landingPageId,
                indexPath,
                indexPageId,
                pages);
    }

    private @Nullable String resolveId(String tenant, String projectId, String path) {
        Optional<DocumentDocument> doc = documentService.findByPath(tenant, projectId, path);
        return doc.map(DocumentDocument::getId).orElse(null);
    }

    @PostMapping("/brain/{tenant}/addon/workspace/page")
    public WorkspacePageView createPage(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody WorkspaceCreatePageRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.CREATE);
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ToolException("title is required");
        }
        String normalised = WorkspaceFolderReader.normaliseFolder(folder);
        String section = sanitiseSegment(request.section());
        String slug = sanitiseSegment(
                request.slug() != null && !request.slug().isBlank()
                        ? request.slug() : request.title());
        if (slug.isEmpty()) slug = "page";

        String basePath = section.isEmpty()
                ? normalised + "/" + slug
                : normalised + "/" + section + "/" + slug;
        String path = uniquePath(tenant, projectId, basePath);

        DocumentDocument doc = canvasService.create(
                tenant, projectId, path,
                request.title(), request.description(),
                List.of(),
                currentUser(httpRequest));

        String relativePath = doc.getPath().substring(normalised.length() + 1);
        log.info("WorkspaceAppController.createPage tenant='{}' folder='{}' path='{}'",
                tenant, normalised, doc.getPath());
        return new WorkspacePageView(
                doc.getId(), doc.getPath(), relativePath, section,
                request.title(), request.description());
    }

    private String uniquePath(String tenant, String projectId, String basePath) {
        String candidate = basePath + ".canvas.md";
        if (documentService.findByPath(tenant, projectId, candidate).isEmpty()) {
            return candidate;
        }
        for (int n = 2; n < 1000; n++) {
            candidate = basePath + "-" + n + ".canvas.md";
            if (documentService.findByPath(tenant, projectId, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new ToolException("Could not find a free slug under '" + basePath + "'");
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        Object o = req.getAttribute("vanceUserId");
        return o instanceof String s ? s : null;
    }

    private static String sanitiseSegment(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    @PostMapping("/brain/{tenant}/addon/workspace/rebuild")
    public WorkspaceRebuildResponse rebuild(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = WorkspaceFolderReader.normaliseFolder(folder);
        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                tenant, projectId, normalised, null, null);
        VanceApplication.RefreshResult result = application.refresh(rc);

        VanceApplication.ArtefactResult index = result.artefacts().isEmpty()
                ? null : result.artefacts().get(0);
        int pages = 0;
        if (index != null && index.stats() != null && index.stats().get("pageCount") instanceof Number n) {
            pages = n.intValue();
        }
        return new WorkspaceRebuildResponse(
                normalised,
                index != null ? index.path() : "",
                index != null ? index.markdownLink() : null,
                pages);
    }
}
