package de.mhus.vance.brain.suggestion;

import de.mhus.vance.api.form.FormSuggestionDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /brain/{tenant}/suggestions/{source}?q=…&projectId=…} — live
 * suggestions for a form field with {@code suggestionsFrom}. An unknown
 * source answers an empty list, so a form outlives the addon that fed it.
 * A given project must be readable; everything beyond that is the source's
 * own check, made with the caller's rights.
 */
@RestController
@RequiredArgsConstructor
public class SuggestionController {

    private final SuggestionService suggestionService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/suggestions/{source}")
    public List<FormSuggestionDto> suggest(
            @PathVariable String tenant,
            @PathVariable String source,
            @RequestParam(name = "q", required = false) @Nullable String query,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable Integer limit,
            HttpServletRequest request) {
        if (query == null || query.isBlank()) return List.of();
        SecurityContext subject = authority.contextOf(request);
        String project = projectId == null || projectId.isBlank() ? null : projectId;
        if (project != null) authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        int max = limit == null ? 8 : Math.max(1, Math.min(limit, SuggestionService.MAX_LIMIT));
        return suggestionService.suggest(
                source,
                new SuggestionRequest(
                        tenant, subject, AccessFilterBase.usernameOrNull(request), project, query.trim(), max));
    }
}
