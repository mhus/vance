package de.mhus.vance.brain.starred;

import de.mhus.vance.api.starred.StarredItemDto;
import de.mhus.vance.api.starred.StarredReconcileDto;
import de.mhus.vance.api.starred.StarredReconcileEntryDto;
import de.mhus.vance.api.starred.StarredRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.shared.starred.StarredService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * User-facing surface for the starred list. Not an admin surface: every user
 * manages their own list, which lives as a document in their hub project.
 *
 * <p>There is deliberately <b>no {@code userId} parameter</b> anywhere here — the
 * user comes from the authenticated request. A parameter would be an IDOR waiting
 * to happen, since the store is addressed by user login.
 *
 * <p>Reads do not resolve entries against their targets, so they enforce nothing
 * per entry; enforcement sits at the target when the user opens it. {@code star}
 * and {@code reconcile} touch the target and enforce {@code READ} on it inside
 * the service. See {@code planning/starred-documents.md}.
 */
@RestController
@RequestMapping("/brain/{tenant}/starred")
@RequiredArgsConstructor
@Slf4j
public class StarredController {

    private final StarredService starredService;
    private final RequestAuthority authority;

    /**
     * The list. {@code all=false} (the default) returns what the landing page
     * shows; {@code all=true} adds the hidden entries for the management view.
     *
     * <p>The filtering happens here, not in the client: a hidden entry must not
     * travel to a surface that would only hide it again with a {@code v-if}.
     */
    @GetMapping
    public List<StarredItemDto> list(
            @PathVariable("tenant") String tenant,
            @RequestParam(name = "all", defaultValue = "false") boolean all,
            HttpServletRequest request) {
        String user = currentUser(request);
        List<StarredItem> items = all
                ? starredService.listResolvable(tenant, user)
                : starredService.listDisplayed(tenant, user);
        return items.stream().map(StarredController::toDto).toList();
    }

    /**
     * The technical lookup, exposed for a "send to"-style caller: the starred
     * application of an app type. 404 when nothing is registered for it — the
     * caller then offers to pick a target instead of failing silently.
     */
    @GetMapping("/by-type/{type}")
    public StarredItemDto findByType(
            @PathVariable("tenant") String tenant,
            @PathVariable("type") String type,
            HttpServletRequest request) {
        String user = currentUser(request);
        return starredService.findByType(tenant, user, type)
                .map(StarredController::toDto)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No starred entry of type '" + type + "'"));
    }

    /** Star a document, or edit an existing entry's authored fields. */
    @PutMapping
    public StarredItemDto star(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody StarredRequest req,
            HttpServletRequest request) {
        String user = currentUser(request);
        try {
            return toDto(starredService.star(
                    tenant, user, req.getProject(), req.getPath(),
                    req.getTitle(), req.getDescription(),
                    req.getHighlight(), req.getHidden(),
                    authority.contextOf(request)));
        } catch (StarredService.StarredException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Unstar. Removes the entry, or — when it carries authored content — only
     * switches it off, so a mis-click cannot eat a typed description.
     */
    @DeleteMapping
    public ResponseEntity<Void> unstar(
            @PathVariable("tenant") String tenant,
            @RequestParam("project") String project,
            @RequestParam("path") String path,
            HttpServletRequest request) {
        String user = currentUser(request);
        boolean changed = starredService.unstar(
                tenant, user, project, path, authority.contextOf(request));
        return changed
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * Toggle landing-page visibility without touching registration — the
     * "show on the start page" checkbox next to the star.
     */
    @PutMapping("/hidden")
    public ResponseEntity<Void> setHidden(
            @PathVariable("tenant") String tenant,
            @RequestParam("project") String project,
            @RequestParam("path") String path,
            @RequestParam("hidden") boolean hidden,
            HttpServletRequest request) {
        String user = currentUser(request);
        boolean found = starredService.setHidden(
                tenant, user, project, path, hidden, authority.contextOf(request));
        return found
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * Resolve every entry against its target: refresh drifted facts, report what
     * could not be resolved. The one place the N lookups happen, and only when
     * asked.
     */
    @PostMapping("/reconcile")
    public StarredReconcileDto reconcile(
            @PathVariable("tenant") String tenant,
            HttpServletRequest request) {
        String user = currentUser(request);
        StarredService.ReconcileResult result =
                starredService.reconcile(tenant, user, authority.contextOf(request));
        return StarredReconcileDto.builder()
                .changed(result.changed())
                .entries(result.entries().stream()
                        .map(e -> StarredReconcileEntryDto.builder()
                                .project(e.project())
                                .path(e.path())
                                .outcome(e.outcome().name().toLowerCase(Locale.ROOT))
                                .message(e.message())
                                .build())
                        .toList())
                .build();
    }

    static StarredItemDto toDto(StarredItem item) {
        return StarredItemDto.builder()
                .project(item.project())
                .path(item.path())
                .kind(item.kind())
                .type(item.type())
                .title(item.title())
                .description(item.description())
                .highlight(item.highlight())
                .enabled(item.enabled())
                .hidden(item.hidden())
                .build();
    }

    private static String currentUser(HttpServletRequest request) {
        Object u = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        return s;
    }
}
