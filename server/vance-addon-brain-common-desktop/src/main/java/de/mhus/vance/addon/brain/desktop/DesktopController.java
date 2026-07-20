package de.mhus.vance.addon.brain.desktop;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoint for the Common Desktop web view. Thin adapter over
 * {@link DesktopStatusService} — computes the live aggregation on
 * demand (the {@code _desktop.md} snapshot is for embedding, not the
 * live view). Project-level READ authority, like the board editors.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class DesktopController {

    private final DesktopStatusService desktopStatusService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/desktop/status")
    public DesktopView getStatus(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = normaliseFolder(folder);
        return desktopStatusService.aggregate(
                tenant, projectId, normalised, currentUser(httpRequest));
    }

    private static String normaliseFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "folder must be provided");
        }
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "folder must not be empty");
        }
        return f;
    }

    private static @Nullable String currentUser(HttpServletRequest httpRequest) {
        Object v = httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        return v instanceof String s ? s : null;
    }
}
