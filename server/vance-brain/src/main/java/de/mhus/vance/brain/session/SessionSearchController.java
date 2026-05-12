package de.mhus.vance.brain.session;

import de.mhus.vance.api.session.SessionSearchHitDto;
import de.mhus.vance.api.session.SessionSearchScope;
import de.mhus.vance.shared.access.AccessFilterBase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST search endpoint for the Web/Mobile session search dialog.
 *
 * <p>Default {@code scope=BOTH} so the user finds sessions both by
 * their metadata (title/tags) and by anything that has ever been
 * said in their chats. {@code includeArchived=true} by default —
 * search is the primary way to recover archived sessions.
 */
@RestController
@RequestMapping("/brain/{tenant}/sessions/search")
@RequiredArgsConstructor
@Slf4j
public class SessionSearchController {

    private static final int DEFAULT_LIMIT = 50;

    private final SessionSearchService searchService;

    @GetMapping
    public List<SessionSearchHitDto> search(
            @PathVariable("tenant") String tenant,
            @RequestParam("q") String query,
            @RequestParam(value = "scope", required = false, defaultValue = "BOTH") String scopeRaw,
            @RequestParam(value = "includeArchived", required = false, defaultValue = "true")
                    boolean includeArchived,
            @RequestParam(value = "limit", required = false, defaultValue = "" + DEFAULT_LIMIT)
                    int limit,
            HttpServletRequest request) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Query parameter 'q' must not be empty");
        }
        SessionSearchScope scope;
        try {
            scope = SessionSearchScope.valueOf(scopeRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown search scope: " + scopeRaw);
        }
        String currentUser = currentUser(request);
        return searchService.search(tenant, currentUser, query, scope, includeArchived, limit);
    }

    private static String currentUser(HttpServletRequest request) {
        Object u = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
        return s;
    }
}
