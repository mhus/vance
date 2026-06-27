package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
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
    private final DocumentLinkBuilder linkBuilder;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/workspace/scan")
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

    @PostMapping("/brain/{tenant}/workspace/rebuild")
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
