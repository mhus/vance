package de.mhus.vance.brain.followup;

import de.mhus.vance.api.followup.FollowUpRequestDto;
import de.mhus.vance.api.followup.FollowUpResponseDto;
import de.mhus.vance.api.followup.FollowUpSuggestionDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for the follow-up suggestion service.
 *
 * <p>{@code POST /brain/{tenant}/follow-up/{project}} with a
 * {@link FollowUpRequestDto} body returns a list of suggestions
 * matching the cursor context. Tenant is checked against the JWT
 * {@code tid} claim by the upstream filter; the project path
 * segment is enforced as a {@link Resource.Project} READ
 * permission.
 *
 * <p>{@code _tenant} is the conventional "no project context"
 * default — pass that as the {@code project} path segment when
 * follow-up should not be coupled to a specific project (e.g. for
 * a generic editor surface).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class FollowUpController {

    private final FollowUpService followUpService;
    private final RequestAuthority authority;

    @PostMapping("/brain/{tenant}/follow-up/{project}")
    public FollowUpResponseDto suggest(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @Valid @RequestBody FollowUpRequestDto body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        if (body.getText() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'text' is required");
        }
        Integer cursor = body.getCursor();
        if (cursor != null && (cursor < 0 || cursor > body.getText().length())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'cursor' must be in [0, text.length()] when set");
        }
        if (body.getCount() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'count' must be >= 1");
        }

        List<FollowUpSuggestionDto> suggestions = followUpService.suggest(
                body.getText(),
                cursor,
                body.getCount(),
                body.getMode(),
                tenant,
                project);

        return FollowUpResponseDto.builder()
                .suggestions(suggestions)
                .build();
    }
}
