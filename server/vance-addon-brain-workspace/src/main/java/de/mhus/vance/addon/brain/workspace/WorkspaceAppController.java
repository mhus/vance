package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.addon.brain.workpage.WorkPageService;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

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
    private final WorkPageService workPageService;
    private final DocumentLinkBuilder linkBuilder;
    private final RequestAuthority authority;
    private final WorkspaceFormService formService;

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
                    p.title(), p.description(), p.icon(), p.sortIndex()));
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

        // Manifest title/description come from the parsed `_app.yaml`
        // body (top-level YAML keys). The DocumentDocument.title is a
        // weaker fallback — it's only set if the upstream tool that
        // created the doc bothered to copy the YAML title across.
        String viewTitle = scan.config().title() != null
                ? scan.config().title()
                : scan.manifest().getTitle();
        return new WorkspaceView(
                normalised,
                viewTitle,
                scan.config().description(),
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

        DocumentDocument doc = workPageService.create(
                tenant, projectId, path,
                request.title(), request.description(),
                List.of(),
                currentUser(httpRequest));

        String relativePath = doc.getPath().substring(normalised.length() + 1);
        log.info("WorkspaceAppController.createPage tenant='{}' folder='{}' path='{}'",
                tenant, normalised, doc.getPath());
        return new WorkspacePageView(
                doc.getId(), doc.getPath(), relativePath, section,
                request.title(), request.description(), null, null);
    }

    private String uniquePath(String tenant, String projectId, String basePath) {
        String candidate = basePath + ".workpage.md";
        if (documentService.findByPath(tenant, projectId, candidate).isEmpty()) {
            return candidate;
        }
        for (int n = 2; n < 1000; n++) {
            candidate = basePath + "-" + n + ".workpage.md";
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

    /**
     * Rename + move + sort-reorder for one page. Each field is optional;
     * fields left {@code null} are not touched. Touches both the
     * front-matter of the page's body and (for section moves) the
     * document's storage path.
     */
    @PutMapping("/brain/{tenant}/addon/workspace/page/{id}")
    public WorkspacePageView updatePage(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody WorkspaceUpdatePageRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        if (request == null) throw new ToolException("body is required");

        String normalised = WorkspaceFolderReader.normaliseFolder(folder);
        DocumentDocument doc = documentService.findById(id)
                .orElseThrow(() -> new ToolException("Unknown page id='" + id + "'"));
        if (!doc.getPath().startsWith(normalised + "/")) {
            throw new ToolException("Page is not inside workspace '" + normalised + "'");
        }

        boolean patchFrontMatter = request.title() != null || request.sortIndex() != null;
        if (patchFrontMatter) {
            patchFrontMatter(doc, request, currentUser(httpRequest));
            // Reload to pick up the storageId / inlineText that the
            // content-write produced.
            doc = documentService.findById(id).orElse(doc);
        }

        if (request.section() != null) {
            String newSection = sanitiseSegment(request.section());
            String leaf = doc.getPath().substring(doc.getPath().lastIndexOf('/') + 1);
            String newPath = newSection.isEmpty()
                    ? normalised + "/" + leaf
                    : normalised + "/" + newSection + "/" + leaf;
            if (!newPath.equals(doc.getPath())) {
                // Avoid clobbering an existing page at the destination.
                if (documentService.findByPath(tenant, projectId, newPath).isPresent()) {
                    throw new ToolException("Target path already exists: '" + newPath + "'");
                }
                doc = documentService.update(
                        id, null, null, null, newPath, null, null, null, null,
                        currentUser(httpRequest));
            }
        }

        // Re-scan to pick up the canonical header values for the view DTO.
        WorkspaceFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);
        for (WorkspacePage p : scan.pages()) {
            if (p.doc().getId().equals(id)) {
                return new WorkspacePageView(
                        p.doc().getId(), p.doc().getPath(), p.relativePath(),
                        p.section(), p.title(), p.description(),
                        p.icon(), p.sortIndex());
            }
        }
        throw new ToolException("Page disappeared after update id='" + id + "'");
    }

    /**
     * Delete a page outright. The underlying document goes through
     * {@code DocumentService.trash} so it lands in the project trash
     * (recoverable) rather than being wiped immediately.
     */
    @DeleteMapping("/brain/{tenant}/addon/workspace/page/{id}")
    public ResponseEntity<Void> deletePage(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.DELETE);
        String normalised = WorkspaceFolderReader.normaliseFolder(folder);
        DocumentDocument doc = documentService.findById(id)
                .orElseThrow(() -> new ToolException("Unknown page id='" + id + "'"));
        if (!doc.getPath().startsWith(normalised + "/")) {
            throw new ToolException("Page is not inside workspace '" + normalised + "'");
        }
        documentService.trash(id, currentUser(httpRequest));
        log.info("WorkspaceAppController.deletePage tenant='{}' folder='{}' path='{}'",
                tenant, normalised, doc.getPath());
        return ResponseEntity.noContent().build();
    }

    /**
     * Read current body, patch the front-matter map with the requested
     * fields, write back. Preserves all other front-matter keys (icon,
     * cover, custom fields) verbatim.
     */
    @SuppressWarnings("unchecked")
    private void patchFrontMatter(
            DocumentDocument doc,
            WorkspaceUpdatePageRequest request,
            @Nullable String editorId) {
        try (InputStream in = documentService.loadContent(doc)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String headerText = "";
            String rest = body;
            if (body.startsWith("---\n")) {
                int end = body.indexOf("\n---\n", 4);
                if (end > 0) {
                    headerText = body.substring(4, end);
                    rest = body.substring(end + 5);
                }
            }
            Map<String, Object> header = new LinkedHashMap<>();
            if (!headerText.isEmpty()) {
                Object loaded = new Yaml().load(headerText);
                if (loaded instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() != null) header.put(e.getKey().toString(), e.getValue());
                    }
                }
            }
            // Make sure $meta.kind stays first — recreate the map in
            // canonical order so the file remains a valid workpage doc
            // even when downstream YAML libs rewrite key order.
            if (!header.containsKey("$meta")) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("kind", "workpage");
                header.put("$meta", meta);
            }
            if (request.title() != null) header.put("title", request.title());
            if (request.sortIndex() != null) header.put("sortIndex", request.sortIndex());

            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setPrettyFlow(false);
            String dumped = new Yaml(opts).dump(header);
            String newBody = "---\n" + dumped + "---\n" + rest;
            documentService.replaceContent(
                    doc.getId(),
                    new java.io.ByteArrayInputStream(newBody.getBytes(StandardCharsets.UTF_8)),
                    "text/markdown",
                    editorId);
        } catch (java.io.IOException e) {
            throw new ToolException("Could not patch front-matter: " + e.getMessage());
        }
    }

    /**
     * Assign {@code sortIndex} {@code 10, 20, 30 …} to the given page
     * ids in the listed order. Used by the sidebar drag-and-drop to
     * persist a new manual ordering inside one section. Pages not
     * referenced in {@code orderedIds} stay untouched, which lets a
     * cross-section drop combine {@link #updatePage} (path-move) with
     * a section-local reorder.
     */
    @PostMapping("/brain/{tenant}/addon/workspace/reorder")
    public void reorderPages(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody WorkspaceReorderRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        if (request == null || request.orderedIds() == null || request.orderedIds().isEmpty()) {
            return;
        }
        String normalised = WorkspaceFolderReader.normaliseFolder(folder);
        String editorId = currentUser(httpRequest);

        double step = 10.0;
        double idx = step;
        for (String id : request.orderedIds()) {
            DocumentDocument doc = documentService.findById(id).orElse(null);
            if (doc == null || !doc.getPath().startsWith(normalised + "/")) continue;
            patchFrontMatter(doc, new WorkspaceUpdatePageRequest(null, null, idx), editorId);
            idx += step;
        }
    }

    /**
     * Rename a section by rewriting the storage path of every page in
     * the workspace whose current section equals {@code from}. The
     * {@code to} value may be empty to lift the pages to top level, or
     * may already exist — in which case the rename merges into it.
     * Section-level metadata is path-only in v1, so this is purely a
     * batched path-move.
     */
    /**
     * Duplicate a page. Copies the body byte-for-byte into a fresh
     * document under the same section; the title front-matter is
     * patched with a {@code "(Copy)"} suffix so the new page is
     * distinguishable from the source. {@code sortIndex} is intentionally
     * dropped so the copy lands at the end of the section.
     */
    @PostMapping("/brain/{tenant}/addon/workspace/page/{id}/duplicate")
    public WorkspacePageView duplicatePage(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.CREATE);
        String normalised = WorkspaceFolderReader.normaliseFolder(folder);
        DocumentDocument src = documentService.findById(id)
                .orElseThrow(() -> new ToolException("Unknown page id='" + id + "'"));
        if (!src.getPath().startsWith(normalised + "/")) {
            throw new ToolException("Page is not inside workspace '" + normalised + "'");
        }

        String body;
        try (InputStream in = documentService.loadContent(src)) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new ToolException("Could not read source page: " + e.getMessage());
        }

        // Compute the destination path: same parent + base slug + "-copy".
        String srcPath = src.getPath();
        int lastSlash = srcPath.lastIndexOf('/');
        String parent = srcPath.substring(0, lastSlash);
        String leaf = srcPath.substring(lastSlash + 1);
        String baseLeaf = leaf.replaceFirst("\\.workpage\\.md$", "");
        String basePath = parent + "/" + baseLeaf + "-copy";
        String newPath = uniquePath(tenant, projectId, basePath);

        // Patch the title in the YAML front-matter so the copy is
        // visually distinct in the sidebar.
        String newBody = patchTitleSuffix(body, " (Copy)");

        DocumentDocument copy = documentService.createText(
                tenant, projectId, newPath, null, null, newBody, currentUser(httpRequest));

        // Re-scan to read the canonical view fields back (icon, section, …).
        WorkspaceFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);
        for (WorkspacePage p : scan.pages()) {
            if (p.doc().getId().equals(copy.getId())) {
                return new WorkspacePageView(
                        p.doc().getId(), p.doc().getPath(), p.relativePath(),
                        p.section(), p.title(), p.description(),
                        p.icon(), p.sortIndex());
            }
        }
        String relativePath = copy.getPath().substring(normalised.length() + 1);
        return new WorkspacePageView(
                copy.getId(), copy.getPath(), relativePath, "",
                copy.getTitle(), null, null, null);
    }

    @SuppressWarnings("unchecked")
    private String patchTitleSuffix(String body, String suffix) {
        if (!body.startsWith("---\n")) return body;
        int end = body.indexOf("\n---\n", 4);
        if (end < 0) return body;
        String headerText = body.substring(4, end);
        Object loaded = new Yaml().load(headerText);
        if (!(loaded instanceof Map<?, ?> m)) return body;
        Map<String, Object> header = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) header.put(e.getKey().toString(), e.getValue());
        }
        Object t = header.get("title");
        String newTitle = (t == null ? "Untitled" : t.toString()) + suffix;
        header.put("title", newTitle);
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String dumped = new Yaml(opts).dump(header);
        return "---\n" + dumped + body.substring(end + 1);
    }

    /**
     * Pin or unpin a page as the workspace's landing page by patching
     * the {@code landingPage} key in the {@code _app.yaml} manifest.
     * Pass {@code pageId=null} to unpin.
     */
    @PostMapping("/brain/{tenant}/addon/workspace/landing")
    public WorkspaceView setLandingPage(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody WorkspaceSetLandingRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        if (request == null) throw new ToolException("body is required");
        String normalised = WorkspaceFolderReader.normaliseFolder(folder);
        String manifestPath = normalised + "/_app.yaml";
        DocumentDocument manifest = documentService.findByPath(tenant, projectId, manifestPath)
                .orElseThrow(() -> new ToolException("No workspace manifest at '" + manifestPath + "'."));

        @Nullable String newLanding = null;
        if (request.pageId() != null && !request.pageId().isBlank()) {
            DocumentDocument page = documentService.findById(request.pageId())
                    .orElseThrow(() -> new ToolException("Unknown page id='" + request.pageId() + "'"));
            if (!page.getPath().startsWith(normalised + "/")) {
                throw new ToolException("Page is not inside workspace '" + normalised + "'");
            }
            newLanding = page.getPath().substring(normalised.length() + 1);
        }

        try (InputStream in = documentService.loadContent(manifest)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String newBody = patchManifestLanding(body, newLanding);
            documentService.replaceContent(
                    manifest.getId(),
                    new java.io.ByteArrayInputStream(newBody.getBytes(StandardCharsets.UTF_8)),
                    "application/yaml",
                    currentUser(httpRequest));
        } catch (java.io.IOException e) {
            throw new ToolException("Could not update manifest: " + e.getMessage());
        }

        // Re-issue a fresh scan so the client gets the new landingPageId.
        return scan(tenant, projectId, folder, httpRequest);
    }

    @SuppressWarnings("unchecked")
    private String patchManifestLanding(String body, @Nullable String newLanding) {
        // The manifest is pure YAML — no `---` front-matter wrapper.
        Object loaded = new Yaml().load(body);
        Map<String, Object> root = loaded instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>();
        Object wsRaw = root.get("workspace");
        Map<String, Object> ws = wsRaw instanceof Map<?, ?> wm
                ? new LinkedHashMap<>((Map<String, Object>) wm)
                : new LinkedHashMap<>();
        if (newLanding == null) ws.remove("landingPage");
        else ws.put("landingPage", newLanding);
        root.put("workspace", ws);
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(opts).dump(root);
    }

    @PostMapping("/brain/{tenant}/addon/workspace/section/rename")
    public void renameSection(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody WorkspaceRenameSectionRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        if (request == null) throw new ToolException("body is required");

        String normalised = WorkspaceFolderReader.normaliseFolder(folder);
        String fromSection = sanitiseSegment(request.from());
        String toSection = sanitiseSegment(request.to());
        if (fromSection.equals(toSection)) return;

        String editorId = currentUser(httpRequest);
        WorkspaceFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);
        for (WorkspacePage page : scan.pages()) {
            if (!fromSection.equals(page.section())) continue;
            DocumentDocument doc = page.doc();
            String leaf = doc.getPath().substring(doc.getPath().lastIndexOf('/') + 1);
            String newPath = toSection.isEmpty()
                    ? normalised + "/" + leaf
                    : normalised + "/" + toSection + "/" + leaf;
            if (newPath.equals(doc.getPath())) continue;
            // Refuse to clobber an unrelated page that already sits at
            // the destination path.
            documentService.findByPath(tenant, projectId, newPath).ifPresent(existing -> {
                if (!existing.getId().equals(doc.getId())) {
                    throw new ToolException("Target path already exists: '" + newPath + "'");
                }
            });
            documentService.update(
                    doc.getId(), null, null, null, newPath, null, null, null, null, editorId);
        }
    }

    /**
     * Project-scoped image search for the asset picker's "Project" tab.
     * Returns documents whose {@code mimeType} starts with
     * {@code image/}, optionally constrained by {@code pathPrefix} and
     * a case-insensitive substring match on {@code path} (=
     * {@code query}). Results are capped at {@code size} (max 200).
     */
    @GetMapping("/brain/{tenant}/addon/workspace/images")
    public WorkspaceImageSearchResponse searchImages(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "pathPrefix", required = false) @Nullable String pathPrefix,
            @RequestParam(value = "query", required = false) @Nullable String query,
            @RequestParam(value = "size", defaultValue = "100") int size,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        DocumentService.ImageListing listing = documentService.listImages(
                tenant, projectId, pathPrefix, query, size);
        List<WorkspaceImageItem> items = new ArrayList<>(listing.items().size());
        for (DocumentService.ImageMatch m : listing.items()) {
            items.add(new WorkspaceImageItem(
                    m.id(), m.path(), m.name(), m.mimeType()));
        }
        return new WorkspaceImageSearchResponse(items, listing.total());
    }

    /**
     * Project-wide recursive document search for the link + embed
     * pickers. Matches the {@code query} as a case-insensitive substring
     * on {@code path} or {@code title}. Useful when the user wants to
     * link to / embed a document anywhere in the project without
     * remembering the exact folder. Optional {@code pathPrefix} scopes
     * the result to one folder prefix — the embed picker's "App" tab
     * passes the current application folder here.
     */
    @GetMapping("/brain/{tenant}/addon/workspace/documents/search")
    public WorkspaceDocumentSearchResponse searchDocuments(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "pathPrefix", required = false) @Nullable String pathPrefix,
            @RequestParam(value = "query", required = false) @Nullable String query,
            @RequestParam(value = "size", defaultValue = "40") int size,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        DocumentService.DocumentListing listing =
                documentService.searchProjectDocuments(tenant, projectId, pathPrefix, query, size);
        List<WorkspaceDocumentItem> items = new ArrayList<>(listing.items().size());
        for (DocumentService.DocumentMatch m : listing.items()) {
            items.add(new WorkspaceDocumentItem(
                    m.id(), m.path(), m.title(), m.kind(), m.mimeType()));
        }
        return new WorkspaceDocumentSearchResponse(items, listing.total());
    }

    /**
     * Load the field schema + current target values for a
     * {@code vance-form} block, resolved from its edit-config document.
     */
    @GetMapping("/brain/{tenant}/addon/workspace/form")
    public WorkspaceFormResponse loadForm(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("config") String config,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        WorkspaceFormService.LoadedForm loaded = formService.loadForm(tenant, projectId, config);
        return new WorkspaceFormResponse(loaded.fields(), loaded.values(), loaded.target());
    }

    /**
     * Persist submitted form values into the edit-config's target data
     * file (flat {@code fieldName -> value} YAML). The {@code onSave}
     * script run + rebuild are a later step — this only writes the data.
     */
    @PostMapping("/brain/{tenant}/addon/workspace/form/save")
    public ResponseEntity<Void> saveForm(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("config") String config,
            @RequestBody WorkspaceFormSaveRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        formService.saveForm(tenant, projectId, config,
                request != null ? request.values() : null,
                currentUser(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /**
     * Create a new edit-config skeleton in the app folder and return its
     * path, so the client can insert a {@code vance-form} block for it.
     */
    @PostMapping("/brain/{tenant}/addon/workspace/form/create")
    public WorkspaceFormCreateResponse createForm(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody WorkspaceFormCreateRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ToolException("form name must not be empty");
        }
        String configPath = formService.createForm(
                tenant, projectId, WorkspaceFolderReader.normaliseFolder(folder),
                request.name(), request.title(), currentUser(httpRequest));
        return new WorkspaceFormCreateResponse(configPath);
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
