package de.mhus.vance.addon.brain.links;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplication.ArtefactResult;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
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

    // ── the capture surface ───────────────────────────────────────
    //
    // Three small routes that together are what an outside tool — a browser
    // extension, a share sheet, a shell alias — needs: the group names for a
    // dropdown, one URL looked up for a badge, and a save. They exist next to
    // the app routes rather than instead of them because the two callers want
    // opposite things: the app wants the whole view after every mutation
    // because a mutation can reorder it; a capture tool wants a few hundred
    // bytes per click and has no list to reorder.
    //
    // They are also what makes the `links-capture` integration profile narrow:
    // a token holding these cannot read the list, reorder it, or delete from
    // it.

    /** The group headings for a "file it under…" dropdown, without the links. */
    @GetMapping("/brain/{tenant}/addon/links/groups")
    public LinkGroupsView listGroups(@PathVariable String tenant,
                                     @RequestParam String projectId,
                                     @RequestParam String folder,
                                     HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        LinksStore.Loaded loaded = store.load(tenant, projectId, folder);
        return new LinkGroupsView(loaded.folder(), loaded.manifestDoc().title(),
                loaded.config().orderedGroups());
    }

    /** Whether one page is already in this list, and what the list says about it. */
    @GetMapping("/brain/{tenant}/addon/links/entry/lookup")
    public LinkLookupView lookupEntry(@PathVariable String tenant,
                                      @RequestParam String projectId,
                                      @RequestParam String folder,
                                      @RequestParam String url,
                                      HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        LinkEntry entry = manifestOps.lookup(tenant, projectId, folder, url);
        return entry == null
                ? LinkLookupView.notFound(LinkUrls.identity(url))
                : LinkLookupView.of(entry);
    }

    /**
     * Save one link and answer in a few hundred bytes.
     *
     * <p>Idempotent on the URL, like {@code POST /entry} — the difference is
     * that this one <em>reports</em> which of the two happened instead of
     * dropping the fact on the floor. Saving the same page twice is the normal
     * case for a capture tool, not an error, so it stays a {@code 200}.
     */
    @PostMapping("/brain/{tenant}/addon/links/capture")
    public LinkCaptureView capture(@PathVariable String tenant,
                                   @RequestParam String projectId,
                                   @RequestParam String folder,
                                   @RequestBody CaptureLinkRequest req,
                                   HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        return LinkCaptureView.of(manifestOps.capture(tenant, projectId, folder, req.url(),
                new LinksManifestOps.LinkFields(req.title(), null, null,
                        req.group(), req.tags(), req.note()),
                currentUser(request)));
    }

    // ── the app surface ───────────────────────────────────────────

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

    /**
     * Mark an entry seen, or put it back on the pile.
     *
     * <p>Its own route rather than a field on the {@code PATCH}: the reading
     * view calls this repeatedly and should not be holding an endpoint that can
     * rewrite a teaser. It is also the one surface an integration profile could
     * reasonably be given beyond capture.
     */
    @PostMapping("/brain/{tenant}/addon/links/entry/viewed")
    public LinksView setViewed(@PathVariable String tenant,
                               @RequestParam String projectId,
                               @RequestParam String folder,
                               @RequestBody SetViewedRequest req,
                               HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        manifestOps.setViewed(tenant, projectId, folder, req.url(), req.viewed(),
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
        // One spelling for "who is doing this". Reading the attribute by
        // hand is what put the wrong name here: nothing ever set
        // "vanceUserId", so every actor recorded from this request was null.
        return AccessFilterBase.usernameOrNull(req);
    }
}
