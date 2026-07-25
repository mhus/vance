package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the binder addon under
 * {@code /brain/{tenant}/addon/binder/...}. Convenience for the Web-UI;
 * the LLM path is the {@code binder_*} tools + generic {@code app_rebuild}.
 * All CRUD on the anchored list lives here (add/remove/reorder/section/
 * landing) — the sidebar's management operations.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class BinderAppController {

    private final BinderApplication application;
    private final BinderResolver resolver;
    private final BinderManifestOps manifestOps;
    private final DocumentService documentService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/binder/scan")
    public BinderView scan(@PathVariable String tenant,
                           @RequestParam String projectId,
                           @RequestParam String folder,
                           HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        BinderResolver.Scan scan = resolver.scan(tenant, projectId, folder);
        List<BinderEntryView> entries = new ArrayList<>();
        for (BinderResolver.ResolvedEntry e : scan.entries()) {
            entries.add(new BinderEntryView(e.ref(), e.id(), e.path(), e.title(),
                    e.kind(), e.mimeType(), e.section(), e.exists()));
        }
        return new BinderView(scan.folder(), scan.manifestDoc().title(),
                scan.manifestDoc().description(), scan.config().landingRef(), entries);
    }

    @PostMapping("/brain/{tenant}/addon/binder/entry")
    public BinderView addEntry(@PathVariable String tenant,
                               @RequestParam String projectId,
                               @RequestParam String folder,
                               @RequestBody AddEntryRequest req,
                               HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.addEntry(tenant, projectId, folder, req.ref(),
                req.section(), req.title(), currentUser(request));
        return scan(tenant, projectId, folder, request);
    }

    @DeleteMapping("/brain/{tenant}/addon/binder/entry")
    public BinderView removeEntry(@PathVariable String tenant,
                                  @RequestParam String projectId,
                                  @RequestParam String folder,
                                  @RequestParam String ref,
                                  HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.removeEntry(tenant, projectId, folder, ref, currentUser(request));
        return scan(tenant, projectId, folder, request);
    }

    @PostMapping("/brain/{tenant}/addon/binder/reorder")
    public BinderView reorder(@PathVariable String tenant,
                              @RequestParam String projectId,
                              @RequestParam String folder,
                              @RequestBody ReorderRequest req,
                              HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.reorder(tenant, projectId, folder,
                req.orderedRefs() != null ? req.orderedRefs() : List.of(), currentUser(request));
        return scan(tenant, projectId, folder, request);
    }

    @PostMapping("/brain/{tenant}/addon/binder/entry/section")
    public BinderView setSection(@PathVariable String tenant,
                                 @RequestParam String projectId,
                                 @RequestParam String folder,
                                 @RequestBody SetSectionRequest req,
                                 HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.setSection(tenant, projectId, folder, req.ref(),
                req.section(), req.title(), currentUser(request));
        return scan(tenant, projectId, folder, request);
    }

    @PostMapping("/brain/{tenant}/addon/binder/landing")
    public BinderView setLanding(@PathVariable String tenant,
                                 @RequestParam String projectId,
                                 @RequestParam String folder,
                                 @RequestBody SetLandingRequest req,
                                 HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.setLanding(tenant, projectId, folder, req.ref(), currentUser(request));
        return scan(tenant, projectId, folder, request);
    }

    @GetMapping("/brain/{tenant}/addon/binder/documents/search")
    public BinderDocSearchResponse searchDocuments(@PathVariable String tenant,
                                                   @RequestParam String projectId,
                                                   @RequestParam(required = false) @Nullable String query,
                                                   @RequestParam(defaultValue = "40") int size,
                                                   HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        int limit = Math.min(Math.max(size, 1), 200);
        DocumentService.DocumentListing listing =
                documentService.searchProjectDocuments(tenant, projectId, null, query, limit);
        List<BinderDocItem> items = new ArrayList<>();
        for (DocumentService.DocumentMatch m : listing.items()) {
            items.add(new BinderDocItem(m.id(), m.path(), m.title(), m.kind(), m.mimeType()));
        }
        return new BinderDocSearchResponse(items, listing.total());
    }

    @PostMapping("/brain/{tenant}/addon/binder/rebuild")
    public RebuildResponse rebuild(@PathVariable String tenant,
                                   @RequestParam String projectId,
                                   @RequestParam String folder,
                                   HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        VanceApplication.RefreshResult result = application.refresh(
                new VanceApplication.RefreshContext(
                        tenant, projectId, folder, currentUser(request), null));
        VanceApplication.ArtefactResult index =
                result.artefacts().isEmpty() ? null : result.artefacts().get(0);
        int entryCount = index != null
                ? ((Number) index.stats().getOrDefault("entryCount", 0)).intValue() : 0;
        long missingCount = index != null
                ? ((Number) index.stats().getOrDefault("missingCount", 0L)).longValue() : 0L;
        return new RebuildResponse(result.folder(),
                index != null ? index.path() : "",
                index != null ? index.markdownLink() : null, entryCount, missingCount);
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        Object o = req.getAttribute("vanceUserId");
        return o instanceof String s ? s : null;
    }
}
