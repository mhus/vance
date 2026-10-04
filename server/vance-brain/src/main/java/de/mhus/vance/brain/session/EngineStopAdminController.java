package de.mhus.vance.brain.session;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Engine stop for the insights inspector: graceful stop and force-stop, per
 * process and for a whole session. This is the mutation half of the
 * inspector's stop buttons — the read half stays on the read-only
 * {@code InsightsAdminController} ({@code planning/session-control.md} §2.2).
 *
 * <p>Two stages, deliberately:
 * <ul>
 *   <li><b>stop</b> — the graceful path: {@code engine.stop} on the
 *       process's lane, {@code CLOSED} with {@code closeReason=STOPPED}.
 *       Bounded wait; if the lane is busy the stop stays queued and still
 *       runs.</li>
 *   <li><b>force-stop</b> — the cut for wedged processes: halt flag out,
 *       {@code CLOSED} written immediately and off-lane with
 *       {@code closeReason=FORCE}, no {@code engine.stop}, nothing waited
 *       for. See {@link SessionLifecycleService#forceStopProcess}.</li>
 * </ul>
 *
 * <p>Both answer 204 and the inspector reloads its rows — the response
 * carries no state on purpose, there is no snapshot to hand back that the
 * caller cannot read from the read endpoints.
 *
 * <p>Admin-gated ({@code Action.ADMIN} on the session), not owner-gated:
 * this surface exists to stop <em>other</em> people's runaways. An owner's
 * own session controls stay on {@code SessionLifecycleController} and the
 * WS {@code process-stop} path.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin")
@RequiredArgsConstructor
@Slf4j
public class EngineStopAdminController {

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final SessionLifecycleService lifecycleService;
    private final RequestAuthority authority;

    @PostMapping("/processes/{processId}/stop")
    public ResponseEntity<Void> stopProcess(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            HttpServletRequest httpRequest) {
        ThinkProcessDocument process = requireProcess(tenant, processId, httpRequest);
        log.info("insights stop: process id='{}' session='{}'", processId, process.getSessionId());
        lifecycleService.stopProcess(process);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/processes/{processId}/force-stop")
    public ResponseEntity<Void> forceStopProcess(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            HttpServletRequest httpRequest) {
        ThinkProcessDocument process = requireProcess(tenant, processId, httpRequest);
        log.info("insights force-stop: process id='{}' session='{}'", processId, process.getSessionId());
        lifecycleService.forceStopProcess(process);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/{sessionId}/stop")
    public ResponseEntity<Void> stopSession(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        SessionDocument session = requireSession(tenant, sessionId, httpRequest);
        lifecycleService.stopAllInSession(tenant, session.getSessionId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/{sessionId}/force-stop")
    public ResponseEntity<Void> forceStopSession(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        SessionDocument session = requireSession(tenant, sessionId, httpRequest);
        lifecycleService.forceStopAllInSession(tenant, session.getSessionId());
        return ResponseEntity.noContent().build();
    }

    private ThinkProcessDocument requireProcess(String tenant, String processId, HttpServletRequest httpRequest) {
        ThinkProcessDocument process = thinkProcessService
                .findById(processId)
                .filter(p -> tenant.equals(p.getTenantId()))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Process '" + processId + "' not found"));
        authority.enforce(
                httpRequest,
                new Resource.Session(tenant, process.getProjectId(), process.getSessionId()),
                Action.ADMIN);
        return process;
    }

    private SessionDocument requireSession(String tenant, String sessionId, HttpServletRequest httpRequest) {
        SessionDocument session = sessionService
                .findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Session '" + sessionId + "' not found"));
        authority.enforce(
                httpRequest,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.ADMIN);
        return session;
    }
}
