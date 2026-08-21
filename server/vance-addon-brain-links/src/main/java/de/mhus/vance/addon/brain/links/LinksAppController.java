package de.mhus.vance.addon.brain.links;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplication.ArtefactResult;
import de.mhus.vance.brain.permission.RequestAuthority;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the links addon under
 * {@code /brain/{tenant}/addon/links/...}. Convenience for the Web-UI; the
 * LLM path is the {@code links_*} tools plus the generic
 * {@code app_rebuild}.
 *
 * <p>Every mutating call answers with the full {@link LinksView} rather
 * than a status. A link manager is edited in quick successive touches and
 * a mutation can move an entry (a group change re-anchors it), so anything
 * less would leave the client guessing at the new order.
 *
 * <p>Authorisation is the project's: {@code READ} to look, {@code WRITE} to
 * change. There is no per-link right — the manifest is one document, and
 * pretending otherwise would be a permission model the storage cannot back.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class LinksAppController {

    private final LinksApplication application;
    private final LinksStore store;
    private final LinksManifestOps manifestOps;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/links/scan")
    public LinksView scan(@PathVariable String tenant,
                          @RequestParam String projectId,
                          @RequestParam String folder,
                          HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return view(tenant, projectId, folder);
    }

    @PostMapping("/brain/{tenant}/addon/links/entry")
    public LinksView addEntry(@PathVariable String tenant,
                              @RequestParam String projectId,
                              @RequestParam String folder,
                              @RequestBody AddLinkRequest req,
                              HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.addEntry(tenant, projectId, folder, req.url(),
                new LinksManifestOps.LinkFields(req.title(), req.teaser(), req.image(),
                        req.group(), req.tags(), req.note()),
                currentUser(request));
        return view(tenant, projectId, folder);
    }

    @PatchMapping("/brain/{tenant}/addon/links/entry")
    public LinksView updateEntry(@PathVariable String tenant,
                                 @RequestParam String projectId,
                                 @RequestParam String folder,
                                 @RequestBody UpdateLinkRequest req,
                                 HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.updateEntry(tenant, projectId, folder, req.url(),
                new LinksManifestOps.LinkFields(req.title(), req.teaser(), req.image(),
                        req.group(), req.tags(), req.note()),
                currentUser(request));
        return view(tenant, projectId, folder);
    }

    @DeleteMapping("/brain/{tenant}/addon/links/entry")
    public LinksView removeEntry(@PathVariable String tenant,
                                 @RequestParam String projectId,
                                 @RequestParam String folder,
                                 @RequestParam String url,
                                 HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.removeEntry(tenant, projectId, folder, url, currentUser(request));
        return view(tenant, projectId, folder);
    }

    @PostMapping("/brain/{tenant}/addon/links/reorder")
    public LinksView reorder(@PathVariable String tenant,
                             @RequestParam String projectId,
                             @RequestParam String folder,
                             @RequestBody ReorderLinksRequest req,
                             HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.reorder(tenant, projectId, folder,
                req.orderedUrls() == null ? List.of() : req.orderedUrls(),
                currentUser(request));
        return view(tenant, projectId, folder);
    }

    @PostMapping("/brain/{tenant}/addon/links/groups")
    public LinksView setGroups(@PathVariable String tenant,
                               @RequestParam String projectId,
                               @RequestParam String folder,
                               @RequestBody GroupsRequest req,
                               HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.setGroups(tenant, projectId, folder,
                req.groups() == null ? List.of() : req.groups(), currentUser(request));
        return view(tenant, projectId, folder);
    }

    @PostMapping("/brain/{tenant}/addon/links/group/rename")
    public LinksView renameGroup(@PathVariable String tenant,
                                 @RequestParam String projectId,
                                 @RequestParam String folder,
                                 @RequestBody RenameGroupRequest req,
                                 HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.renameGroup(tenant, projectId, folder, req.from(), req.to(),
                currentUser(request));
        return view(tenant, projectId, folder);
    }

    @PostMapping("/brain/{tenant}/addon/links/rebuild")
    public LinksRebuildResponse rebuild(@PathVariable String tenant,
                                        @RequestParam String projectId,
                                        @RequestParam String folder,
                                        HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        VanceApplication.RefreshResult result = application.refresh(
                new VanceApplication.RefreshContext(
                        tenant, projectId, folder, currentUser(request), null));
        ArtefactResult index =
                result.artefacts().isEmpty() ? null : result.artefacts().getFirst();
        return new LinksRebuildResponse(result.folder(),
                index == null ? "" : index.path(),
                index == null ? null : index.markdownLink(),
                statInt(index, "entryCount"),
                statInt(index, "groupCount"));
    }

    // ── helpers ───────────────────────────────────────────────────

    private LinksView view(String tenant, String projectId, String folder) {
        LinksStore.Loaded loaded = store.load(tenant, projectId, folder);
        List<LinkEntryView> entries = new ArrayList<>();
        for (LinkEntry e : loaded.config().entries()) {
            entries.add(LinkEntryView.of(e));
        }
        return new LinksView(loaded.folder(), loaded.manifestDoc().title(),
                loaded.manifestDoc().description(), loaded.config().orderedGroups(), entries);
    }

    private static int statInt(@Nullable ArtefactResult index, String key) {
        if (index == null) return 0;
        Object v = index.stats().get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        Object o = req.getAttribute("vanceUserId");
        return o instanceof String s ? s : null;
    }
}
