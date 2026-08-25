package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.brain.applications.VanceApplication.RefreshContext;
import de.mhus.vance.brain.permission.RequestAuthority;
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
import org.springframework.web.bind.annotation.RestController;

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
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/bistromath/scan")
    public AppScan scan(@PathVariable String tenant,
                        @RequestParam String projectId,
                        @RequestParam String folder,
                        HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return viewService.scan(tenant, projectId, folder);
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
            return viewService.viewByPath(tenant, projectId, path.trim());
        }
        if (folder == null || folder.isBlank()) {
            throw new ToolException("Name the view: either `folder` (and optionally `handle`)"
                    + " or `path`.");
        }
        return viewService.view(tenant, projectId, folder, handle);
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
        application.refresh(new RefreshContext(tenant, projectId, folder,
                currentUser(request), null));
        return viewService.scan(tenant, projectId, folder);
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        Object o = req.getAttribute("vanceUserId");
        return o instanceof String s ? s : null;
    }
}
