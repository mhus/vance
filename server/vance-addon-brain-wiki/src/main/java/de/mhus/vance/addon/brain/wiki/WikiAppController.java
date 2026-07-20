package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the interactive Wiki editor in the Web-UI. Thin
 * adapter over {@link WikiApplication} + {@link WikiService} +
 * {@link WikiFolderReader} — no business logic here.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class WikiAppController {

    private final WikiApplication application;
    private final WikiFolderReader folderReader;
    private final WikiService wikiService;
    private final DocumentService documentService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/wiki/scan")
    public WikiView scan(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = WikiFolderReader.normaliseFolder(folder);
        WikiFolderReader.Scan scanResult = folderReader.scan(tenant, projectId, normalised);

        List<WikiPageView> pages = new ArrayList<>();
        for (WikiPage p : scanResult.pages()) pages.add(toPageView(p));

        List<WikiSpaceView> spaces = new ArrayList<>();
        for (String space : scanResult.spaces()) {
            int count = wikiService.pagesInSpace(scanResult, space).size();
            String mainPath = spaceFilePath(normalised, space, WikiFolderReader.MAIN_PAGE + WikiFolderReader.PAGE_EXTENSION);
            String indexPath = WikiFolderReader.indexPathFor(
                    normalised, space, scanResult.config().index().outputPath());
            String spaceTitle = space.isEmpty()
                    ? firstNonBlank(scanResult.config().title(), normalised)
                    : WikiFolderReader.humanise(leaf(space));
            spaces.add(new WikiSpaceView(
                    space, spaceTitle, count,
                    existsPath(tenant, projectId, mainPath) ? mainPath : null,
                    resolveId(tenant, projectId, mainPath),
                    indexPath,
                    resolveId(tenant, projectId, indexPath)));
        }

        String rootMainPath = normalised + "/" + WikiFolderReader.MAIN_PAGE + WikiFolderReader.PAGE_EXTENSION;
        String rootIndexPath = WikiFolderReader.indexPathFor(
                normalised, "", scanResult.config().index().outputPath());

        String viewTitle = firstNonBlank(scanResult.config().title(), scanResult.manifest().getTitle());
        return new WikiView(
                normalised,
                viewTitle,
                scanResult.config().description(),
                existsPath(tenant, projectId, rootMainPath) ? rootMainPath : null,
                resolveId(tenant, projectId, rootMainPath),
                rootIndexPath,
                resolveId(tenant, projectId, rootIndexPath),
                spaces,
                pages);
    }

    @PostMapping("/brain/{tenant}/addon/wiki/rebuild")
    public WikiRebuildResponse rebuild(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = WikiFolderReader.normaliseFolder(folder);
        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                tenant, projectId, normalised, currentUser(httpRequest), null);
        VanceApplication.RefreshResult result = application.refresh(rc);

        WikiFolderReader.Scan scanResult = folderReader.scan(tenant, projectId, normalised);
        String rootIndexPath = WikiFolderReader.indexPathFor(
                normalised, "", scanResult.config().index().outputPath());
        String backlinksPath = result.artefacts().stream()
                .filter(a -> "backlinks".equals(a.name()))
                .map(VanceApplication.ArtefactResult::path)
                .findFirst().orElse(null);
        long indexCount = result.artefacts().stream()
                .filter(a -> !"backlinks".equals(a.name())).count();

        return new WikiRebuildResponse(
                normalised, rootIndexPath, backlinksPath,
                (int) indexCount, scanResult.pages().size(), scanResult.spaces().size());
    }

    @PostMapping("/brain/{tenant}/addon/wiki/page")
    public WikiPageView createPage(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody WikiCreatePageRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.CREATE);
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ToolException("title is required");
        }
        String normalised = WikiFolderReader.normaliseFolder(folder);
        DocumentDocument doc = wikiService.createPage(
                tenant, projectId, normalised, request.space(),
                request.title(), currentUser(httpRequest));

        log.info("WikiAppController.createPage tenant='{}' folder='{}' path='{}'",
                tenant, normalised, doc.getPath());
        return toPageView(pageFromDoc(normalised, doc, request.title()));
    }

    @DeleteMapping("/brain/{tenant}/addon/wiki/page/{id}")
    public ResponseEntity<Void> deletePage(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.DELETE);
        String normalised = WikiFolderReader.normaliseFolder(folder);
        DocumentDocument doc = documentService.findById(id)
                .orElseThrow(() -> new ToolException("Unknown page id='" + id + "'"));
        if (!doc.getPath().startsWith(normalised + "/")) {
            throw new ToolException("Page is not inside wiki '" + normalised + "'");
        }
        documentService.trash(id, currentUser(httpRequest));
        log.info("WikiAppController.deletePage tenant='{}' folder='{}' path='{}'",
                tenant, normalised, doc.getPath());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/brain/{tenant}/addon/wiki/resolve")
    public WikiResolveResponse resolve(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("target") String target,
            @RequestParam(value = "space", required = false) @Nullable String space,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = WikiFolderReader.normaliseFolder(folder);
        WikiFolderReader.Scan scanResult = folderReader.scan(tenant, projectId, normalised);
        WikiService.Resolution r = wikiService.resolve(
                scanResult, space == null ? "" : WikiService.normaliseSpace(space), target);

        String createPath = spaceFilePath(
                normalised, r.createSpace(), r.slug() + WikiFolderReader.PAGE_EXTENSION);
        @Nullable WikiPage page = r.page();
        return new WikiResolveResponse(
                target, r.exists(), r.ambiguous(), r.slug(),
                page != null ? page.doc().getPath() : null,
                page != null ? page.doc().getId() : null,
                page != null ? page.space() : null,
                r.createSpace(),
                createPath);
    }

    @GetMapping("/brain/{tenant}/addon/wiki/backlinks")
    public WikiBacklinksView backlinks(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = WikiFolderReader.normaliseFolder(folder);
        WikiFolderReader.Scan scanResult = folderReader.scan(tenant, projectId, normalised);
        Map<String, List<String>> graph = wikiService.buildBacklinks(scanResult);

        String prefix = normalised + "/";
        String targetRel = path.startsWith(prefix) ? path.substring(prefix.length()) : path;

        Map<String, WikiPage> byRel = new LinkedHashMap<>();
        for (WikiPage p : scanResult.pages()) byRel.put(p.relativePath(), p);

        List<WikiPageView> inbound = new ArrayList<>();
        for (String rel : graph.getOrDefault(targetRel, List.of())) {
            WikiPage p = byRel.get(rel);
            if (p != null) inbound.add(toPageView(p));
        }
        return new WikiBacklinksView(path, inbound);
    }

    @GetMapping("/brain/{tenant}/addon/wiki/recent")
    public List<WikiPageView> recent(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = WikiFolderReader.normaliseFolder(folder);
        WikiFolderReader.Scan scanResult = folderReader.scan(tenant, projectId, normalised);
        List<WikiPageView> out = new ArrayList<>();
        for (WikiPage p : wikiService.recentlyModified(scanResult, limit)) out.add(toPageView(p));
        return out;
    }

    @GetMapping("/brain/{tenant}/addon/wiki/documents/search")
    public WikiDocumentSearchResponse searchDocuments(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "pathPrefix", required = false) @Nullable String pathPrefix,
            @RequestParam(value = "query", required = false) @Nullable String query,
            @RequestParam(value = "size", defaultValue = "40") int size,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        DocumentService.DocumentListing listing =
                documentService.searchProjectDocuments(tenant, projectId, pathPrefix, query, size);
        List<WikiDocumentItem> items = new ArrayList<>(listing.items().size());
        for (DocumentService.DocumentMatch m : listing.items()) {
            items.add(new WikiDocumentItem(m.id(), m.path(), m.title(), m.kind(), m.mimeType()));
        }
        return new WikiDocumentSearchResponse(items, listing.total());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private WikiPageView toPageView(WikiPage p) {
        return new WikiPageView(
                p.doc().getId(), p.doc().getPath(), p.relativePath(),
                p.space(), p.slug(), p.title(), p.main());
    }

    /** Build a lightweight {@link WikiPage} from a freshly stored document. */
    private static WikiPage pageFromDoc(String folder, DocumentDocument doc, String title) {
        String rel = doc.getPath().startsWith(folder + "/")
                ? doc.getPath().substring(folder.length() + 1)
                : doc.getPath();
        int slash = rel.lastIndexOf('/');
        String space = slash < 0 ? "" : rel.substring(0, slash);
        String leaf = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
        int dot = leaf.indexOf('.');
        String slug = dot < 0 ? leaf : leaf.substring(0, dot);
        boolean main = WikiFolderReader.MAIN_PAGE.equals(slug);
        return new WikiPage(doc, rel, space, slug, title, main, List.of());
    }

    private static String spaceFilePath(String folder, String space, String leaf) {
        return space.isEmpty() ? folder + "/" + leaf : folder + "/" + space + "/" + leaf;
    }

    private @Nullable String resolveId(String tenant, String projectId, String path) {
        Optional<DocumentDocument> doc = documentService.findByPath(tenant, projectId, path);
        return doc.map(DocumentDocument::getId).orElse(null);
    }

    private boolean existsPath(String tenant, String projectId, String path) {
        return documentService.findByPath(tenant, projectId, path).isPresent();
    }

    private static String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String leaf(String space) {
        int slash = space.lastIndexOf('/');
        return slash < 0 ? space : space.substring(slash + 1);
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        Object o = req.getAttribute("vanceUserId");
        return o instanceof String s ? s : null;
    }
}
