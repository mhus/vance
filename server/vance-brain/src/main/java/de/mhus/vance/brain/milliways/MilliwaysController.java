package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.milliways.ShareContextRequest;
import de.mhus.vance.api.milliways.ShareFormDto;
import de.mhus.vance.api.milliways.ShareHandlerDto;
import de.mhus.vance.api.milliways.ShareResultDto;
import de.mhus.vance.api.milliways.ShareSubjectDto;
import de.mhus.vance.api.milliways.ShareSubmitRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentRef;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * User-facing surface for sharing — deliberately not under {@code /admin}:
 * showing a colleague something is ordinary work.
 *
 * <p>All three operations are {@code POST}, including the two reads. The
 * subject carries a link and a snippet, which do not fit in a query string;
 * two paths for the same thing would be worse than one unfashionable verb.
 *
 * <p>Handler list and handler form stay two calls: the list is cheap, a form
 * costs a user list or a pack list, and merely opening the share menu should
 * not hand out a user directory.
 *
 * <p>Authorization lives in {@link MilliwaysService}, not here — it guards all
 * three operations, so it belongs at the single place they share.
 */
@RestController
@RequestMapping("/brain/{tenant}/share")
@RequiredArgsConstructor
@Slf4j
public class MilliwaysController {

    private final MilliwaysService milliwaysService;
    private final RequestAuthority authority;

    @PostMapping("/handlers")
    public List<ShareHandlerDto> handlers(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody ShareContextRequest body,
            HttpServletRequest request) {
        return call(() -> milliwaysService.listHandlers(
                target(tenant, body.getProjectId(), body.getSubject(), request)));
    }

    @PostMapping("/handlers/{handlerId}/form")
    public ShareFormDto form(
            @PathVariable("tenant") String tenant,
            @PathVariable("handlerId") String handlerId,
            @Valid @RequestBody ShareContextRequest body,
            HttpServletRequest request) {
        return call(() -> milliwaysService.form(
                handlerId, target(tenant, body.getProjectId(), body.getSubject(), request)));
    }

    @PostMapping("/handlers/{handlerId}")
    public ShareResultDto share(
            @PathVariable("tenant") String tenant,
            @PathVariable("handlerId") String handlerId,
            @Valid @RequestBody ShareSubmitRequest body,
            HttpServletRequest request) {
        return call(() -> milliwaysService.share(
                handlerId,
                target(tenant, body.getProjectId(), body.getSubject(), request),
                body.getValues()));
    }

    // ──────────────────── internals ────────────────────

    private ShareTarget target(
            String tenant, String projectId, ShareSubjectDto subject, HttpServletRequest request) {
        return new ShareTarget(
                authority.contextOf(request), tenant, projectId, subjectOf(projectId, subject));
    }

    /**
     * Wire subject → domain subject. {@code documentPath} is a path inside the
     * request's project, so the fully-qualified {@link DocumentRef} is built
     * here rather than trusted from the client.
     *
     * <p>The record's own constructor enforces "at least one of link / snippet
     * / document" and throws a {@link ShareException}, which {@link #call}
     * turns into 422 — a request body that names nothing to show is a
     * validation error, not a server fault.
     */
    private static ShareSubject subjectOf(String projectId, ShareSubjectDto dto) {
        String path = blankToNull(dto.getDocumentPath());
        return new ShareSubject(
                blankToNull(dto.getTitle()),
                blankToNull(dto.getLink()),
                blankToNull(dto.getSnippet()),
                path == null ? null : DocumentRef.of(projectId, path));
    }

    private static @Nullable String blankToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /**
     * Maps the refusal kinds. {@code PermissionDeniedException} is not among
     * them — {@code PermissionExceptionAdvice} already turns it into 403 for
     * every controller.
     *
     * <p>{@link ShareTransportException} is 502 and deliberately not a
     * {@link ShareException}: "the relay refused" is not something the user
     * can fix by editing the form.
     */
    private static <T> T call(Supplier<T> body) {
        try {
            return body.get();
        } catch (ShareNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ShareUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (ShareException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (ShareTransportException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }
}
