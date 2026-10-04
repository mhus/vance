package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.insights.TrillianInsightsDto;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mutation half of the insights Trillian tab: pause and resume the
 * user-loop of one Trillian pair. The read half stays on the read-only
 * insights controller — this class exists so the inspector's read surface
 * can be audited as read-only (see {@code planning/session-control.md} §2.2).
 *
 * <p>Same semantics as {@code //trillian stop|continue} — both go through
 * {@link TrillianInternalApi#pausePeer}/{@link
 * TrillianInternalApi#resumePeer}: the halt flag goes out out-of-band and
 * immediately, {@code PAUSED} lands at the next safe boundary, and a
 * resume schedules a lane turn so queued work is picked up. Running task
 * workers are never touched by a pause.
 *
 * <p>Both endpoints answer with the refreshed state of the pair
 * ({@link TrillianInsightsDto}), so a caller can update its row in place
 * instead of refetching the whole list.
 */
@Controller
@RequestMapping("/brain/{tenant}/admin/trillian")
@RequiredArgsConstructor
@Slf4j
public class TrillianLoopController {

    private final ThinkProcessService thinkProcessService;
    private final TrillianInternalApi api;
    private final TrillianInsightsService insightsService;
    private final RequestAuthority authority;

    @PostMapping("/{controlProcessId}/pause")
    @ResponseBody
    public TrillianInsightsDto pause(
            @PathVariable("tenant") String tenant,
            @PathVariable("controlProcessId") String controlProcessId,
            HttpServletRequest httpRequest) {
        return act(tenant, controlProcessId, httpRequest, "paused", api::pausePeer);
    }

    @PostMapping("/{controlProcessId}/resume")
    @ResponseBody
    public TrillianInsightsDto resume(
            @PathVariable("tenant") String tenant,
            @PathVariable("controlProcessId") String controlProcessId,
            HttpServletRequest httpRequest) {
        return act(tenant, controlProcessId, httpRequest, "resumed", api::resumePeer);
    }

    private TrillianInsightsDto act(
            String tenant,
            String controlProcessId,
            HttpServletRequest httpRequest,
            String verb,
            Function<ThinkProcessDocument, ThinkProcessStatus> action) {
        ThinkProcessDocument control = thinkProcessService
                .findById(controlProcessId)
                .filter(c -> tenant.equals(c.getTenantId()))
                .filter(c -> TrillianSessionBootstrapper.CONTROL_ENGINE_NAME.equals(c.getThinkEngine()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Trillian control process '" + controlProcessId + "' not found"));
        authority.enforce(
                httpRequest,
                new Resource.Session(tenant, control.getProjectId(), control.getSessionId()),
                Action.ADMIN);
        ThinkProcessDocument peer = api.findPeer(control.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Trillian control process '" + controlProcessId + "' has no paired user-loop"));
        ThinkProcessStatus status = action.apply(peer);
        log.info("Trillian loop {} sessionId='{}' status={}", verb, control.getSessionId(), status);
        return insightsService
                .describe(tenant, controlProcessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Trillian control process '" + controlProcessId + "' not found"));
    }
}
