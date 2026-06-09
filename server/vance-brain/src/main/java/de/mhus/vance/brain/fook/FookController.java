package de.mhus.vance.brain.fook;

import de.mhus.vance.api.fook.FookSubmissionRequestDto;
import de.mhus.vance.api.fook.FookSubmissionResponseDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for UI-driven Fook submissions. The web Fook
 * button (in the user-menu), the foot CLI {@code /support}
 * command, and any future client all POST here. Engine-driven
 * submissions go through the {@code vance_support_request} tool —
 * not this endpoint.
 *
 * <p>{@code POST /brain/{tenant}/fook/submit} with a
 * {@link FookSubmissionRequestDto} body returns a
 * {@link FookSubmissionResponseDto} with the assigned submission
 * id; triage runs asynchronously and the reporter sees the result
 * as an inbox item once Fook has decided.
 *
 * <p>Authorization: {@link Action#WRITE} on
 * {@link Resource.Tenant} — any authenticated user in the tenant
 * may file a support request. The userId is taken from the JWT
 * via {@link RequestAuthority#contextOf}; the tenant path segment
 * must match the JWT's tenant (the upstream filter enforces that).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class FookController {

    private final FookService fookService;
    private final RequestAuthority authority;

    @PostMapping("/brain/{tenant}/fook/submit")
    public FookSubmissionResponseDto submit(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody FookSubmissionRequestDto body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Tenant(tenant), Action.WRITE);
        SecurityContext sc = authority.contextOf(request);

        if (body.getText() == null || body.getText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'text' is required");
        }

        SubmissionRequest req = SubmissionRequest.builder()
                .text(body.getText())
                .reporter(TicketReporter.builder()
                        .kind(TicketReporter.Kind.USER_DIRECT)
                        .userId(sc.subjectId())
                        .tenantId(tenant)
                        .build())
                .context(buildContext(body))
                .build();

        String submissionId = fookService.submit(req);
        return FookSubmissionResponseDto.builder()
                .submissionId(submissionId)
                .status("queued")
                .build();
    }

    private static TicketContext buildContext(FookSubmissionRequestDto body) {
        boolean hasProject = body.getProjectId() != null && !body.getProjectId().isBlank();
        boolean hasSession = body.getSessionId() != null && !body.getSessionId().isBlank();
        if (!hasProject && !hasSession) return null;
        return TicketContext.builder()
                .projectId(hasProject ? body.getProjectId() : null)
                .sessionId(hasSession ? body.getSessionId() : null)
                .build();
    }
}
