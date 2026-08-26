package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.brain.applications.VanceApplication.RefreshContext;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface of the Bistromath runtime, under
 * {@code /brain/{tenant}/addon/bistromath/...} — four routes, and each one had
 * to argue for itself.
 *
 * <p><b>No data route.</b> An earlier draft had a {@code /table} endpoint, then
 * a {@code /rows} one. Both were wrong for the same reason: everything they
 * would do already exists as a generic document route — list a folder, read
 * content, write content. A second way to read the same documents would have
 * put "what a row is" in the backend, where the runtime cannot see it anyway,
 * and made the app's data model a server concept instead of a few lines in the
 * app's own program.
 *
 * <p>So the program lists and reads documents through the ordinary document API
 * in the browser, and these routes answer only what is genuinely about *this
 * app*: what views exist, what one view looks like, re-check it — and the source
 * of one entry in the load list, which is the one thing the document API
 * genuinely cannot do, because a bundled library is a classpath resource and not
 * a document.
 *
 * <p>Authorisation is the project's: {@code READ} to look, {@code WRITE} to
 * rebuild. There is no per-view right — a view is a document and the document
 * layer already answers that question.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class BistromathAppController {

    private final BistromathViewService viewService;
    private final RequireResolver requireResolver;
    private final BistromathApplication application;
    private final AppReleaseRequestService releaseRequests;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/bistromath/scan")
    public AppScan scan(@PathVariable String tenant,
                        @RequestParam String projectId,
                        @RequestParam String folder,
                        HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return refuseIfForbidden(viewService.scan(tenant, projectId, folder));
    }

    /**
     * Turn a {@code forbidden} policy into a refusal rather than an app.
     *
     * <p>The client would decline to mount either way — the enforcement is
     * complete there, because a guest cannot make an authenticated call and so
     * every call goes through the host. Refusing here as well means there is
     * *nothing to mount*: no view tree, no program, no load list on the wire.
     * A client that decides not to render is one bug away from rendering; a
     * client that received nothing is not.
     *
     * <p>403 rather than 404: the app exists, and saying so is not a leak —
     * whoever asks already has {@code Project READ} and can see the folder.
     * Pretending it is absent would send them looking for a document.
     */
    private static AppScan refuseIfForbidden(AppScan scan) {
        if (scan.policy().forbids()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Custom applications are not permitted here."
                            + " A tenant admin decides this in _vance/config/applications.yaml"
                            + " (in the _tenant project).");
        }
        return scan;
    }

    /**
     * One view, named either by its app and handle or by its own path.
     *
     * <p>Two ways into one route rather than a fourth route: it is the same
     * question — "give me this view, parsed" — asked from the app, where a view
     * has a handle, and from the Cortex, where a document has a path.
     */
    @GetMapping("/brain/{tenant}/addon/bistromath/view")
    public RenderedView view(@PathVariable String tenant,
                             @RequestParam String projectId,
                             @RequestParam(required = false) @Nullable String folder,
                             @RequestParam(required = false) @Nullable String handle,
                             @RequestParam(required = false) @Nullable String path,
                             HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        if (path != null && !path.isBlank()) {
            // A view opened by its own path in the Cortex — the folder is the
            // document's parent, and the same policy has to apply. Otherwise
            // `forbidden` would be a rule about how an app is *entered*.
            refuseIfForbidden(viewService.scan(tenant, projectId, folderOf(path.trim())));
            return viewService.viewByPath(tenant, projectId, path.trim());
        }
        if (folder == null || folder.isBlank()) {
            throw new ToolException("Name the view: either `folder` (and optionally `handle`)"
                    + " or `path`.");
        }
        refuseIfForbidden(viewService.scan(tenant, projectId, folder));
        return viewService.view(tenant, projectId, folder, handle);
    }

    /** The folder a document path lives in. */
    private static String folderOf(String documentPath) {
        int slash = documentPath.lastIndexOf('/');
        return slash < 0 ? "" : documentPath.substring(0, slash);
    }

    /**
     * Whether this app may be opened, and whether asking is possible.
     *
     * <p>Called by the client after a refusal, to decide whether to offer a
     * button. Its own route so that the decision does not depend on reading the
     * refusal's wording.
     */
    @GetMapping("/brain/{tenant}/addon/bistromath/release-status")
    public AppReleaseRequestService.Status releaseStatus(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String folder,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return releaseRequests.status(tenant, projectId, folder);
    }

    /**
     * Ask an admin to release this app.
     *
     * <p>The way out of the dead end that {@code forbidden} would otherwise be.
     * {@code Project READ} — asking is not a change, and the reader who can see
     * the folder is the one who wants to open it. The decision is somebody
     * else's, and refusing this route to them would only mean they ask in a
     * chat instead.
     */
    @PostMapping("/brain/{tenant}/addon/bistromath/release-request")
    public AppReleaseRequestService.Receipt requestRelease(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String folder,
            @RequestParam(required = false) @Nullable String reason,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        String user = currentUser(request);
        if (user == null || user.isBlank()) {
            // A request has to have an asker: the item is assigned to a decider
            // and shows who wants it, and "somebody" is not an answer they can
            // act on.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a signed-in user can request a release.");
        }
        try {
            return releaseRequests.request(tenant, projectId, folder, user, reason);
        } catch (ToolException e) {
            // A refusal, not a fault: the tenant has no release path, an admin
            // already decided explicitly, or the app was refused before. 500
            // would send the reader looking for a broken server.
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /**
     * The source of one document in the app's load list.
     *
     * <p>The fourth route, and the first one that does not duplicate something
     * the generic document API already does — because for a **bundled** library
     * there is nothing to duplicate: it is a classpath resource with no document
     * row, so `documents/by-path` answers 404. Serving it here keeps the cascade
     * live (a project can still override a bundled library by writing a
     * document at the same path) where mirroring at boot would freeze it.
     *
     * <p>Plain text, not JSON: it is source code on its way into an evaluator.
     */
    @GetMapping(value = "/brain/{tenant}/addon/bistromath/script",
                produces = "text/plain; charset=utf-8")
    public String script(@PathVariable String tenant,
                         @RequestParam String projectId,
                         @RequestParam String path,
                         HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return requireResolver.read(tenant, projectId, path);
    }

    /**
     * Re-read every view and rewrite the index.
     *
     * <p>Answers with the fresh {@link AppScan} rather than the refresh result:
     * the caller is a client about to re-render, and the detail is in the
     * regenerated index for anybody who wants it.
     */
    @PostMapping("/brain/{tenant}/addon/bistromath/rebuild")
    public AppScan rebuild(@PathVariable String tenant,
                           @RequestParam String projectId,
                           @RequestParam String folder,
                           HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        // The policy is checked *before* the rebuild: a forbidden app should
        // not have its index rewritten as a side effect of asking about it.
        refuseIfForbidden(viewService.scan(tenant, projectId, folder));
        application.refresh(new RefreshContext(tenant, projectId, folder,
                currentUser(request), null));
        return viewService.scan(tenant, projectId, folder);
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        // One spelling for "who is doing this". Reading the attribute by
        // hand is what put the wrong name here: nothing ever set
        // "vanceUserId", so every actor recorded from this request was null.
        return AccessFilterBase.usernameOrNull(req);
    }
}
