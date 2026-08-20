package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.milliways.ShareFormDto;
import de.mhus.vance.api.milliways.ShareHandlerDto;
import de.mhus.vance.api.milliways.ShareResultDto;
import de.mhus.vance.api.milliways.ShareSubmitRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * User-facing surface for sharing a document — deliberately not under
 * {@code /admin}: showing a colleague a file is ordinary work.
 *
 * <p>Handler list and handler form are two calls, not one. The list is
 * cheap; a form costs a user list or a pack list, and merely opening the
 * share menu should not hand out a user directory.
 *
 * <p>{@code Document READ} for the sharer is enforced in
 * {@link MilliwaysService}, not here — it guards all three operations
 * including the listing, so it belongs at the single place they share.
 */
@RestController
@RequestMapping("/brain/{tenant}/share")
@RequiredArgsConstructor
@Slf4j
public class MilliwaysController {

    private final MilliwaysService milliwaysService;
    private final RequestAuthority authority;

    @GetMapping("/handlers")
    public List<ShareHandlerDto> handlers(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            HttpServletRequest request) {
        return call(() -> milliwaysService.listHandlers(target(tenant, projectId, path, request)));
    }

    @GetMapping("/handlers/{handlerId}/form")
    public ShareFormDto form(
            @PathVariable("tenant") String tenant,
            @PathVariable("handlerId") String handlerId,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            HttpServletRequest request) {
        return call(() -> milliwaysService.form(
                handlerId, target(tenant, projectId, path, request)));
    }

    @PostMapping("/handlers/{handlerId}")
    public ShareResultDto share(
            @PathVariable("tenant") String tenant,
            @PathVariable("handlerId") String handlerId,
            @Valid @RequestBody ShareSubmitRequest body,
            HttpServletRequest request) {
        return call(() -> milliwaysService.share(
                handlerId,
                target(tenant, body.getProjectId(), body.getPath(), request),
                body.getValues()));
    }

    // ──────────────────── internals ────────────────────

    private ShareTarget target(
            String tenant, String projectId, String path, HttpServletRequest request) {
        return new ShareTarget(authority.contextOf(request), tenant, projectId, path);
    }

    /**
     * Maps the refusal kinds. {@code PermissionDeniedException} is not
     * among them — {@code PermissionExceptionAdvice} already turns it into
     * 403 for every controller.
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
