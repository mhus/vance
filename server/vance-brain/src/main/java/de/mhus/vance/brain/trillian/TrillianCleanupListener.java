package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import de.mhus.vance.shared.user.UserService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Cleans up the per-session Trillian service-account when a
 * Trillian-Control process closes — that is, when the session
 * itself is shutting down via the standard close-cascade.
 *
 * <p>Listens for {@link ThinkProcessStatusChangedEvent}; reacts only
 * when (a) {@code newStatus == CLOSED}, (b) the closing process's
 * engine is {@value TrillianSessionBootstrapper#CONTROL_ENGINE_NAME}
 * (Nature-agnostic), and (c) the process carries a
 * {@link TrillianSessionBootstrapper#PARAM_TRILLIAN_USER_NAME} in its
 * {@code engineParams}. Deletes the bound service-account so the
 * tenant namespace stays clean.
 *
 * <p>Nature-0 contract is ephemeral users: each session mints a
 * fresh one and tears it down on close. Persistent Trillians
 * (Nature-A+) will need a different lifecycle.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianCleanupListener {

    private final ThinkProcessService thinkProcessService;
    private final UserService userService;
    private final SessionLifecycleService sessionLifecycleService;

    @EventListener
    public void onProcessStatusChanged(ThinkProcessStatusChangedEvent event) {
        if (event.newStatus() != ThinkProcessStatus.CLOSED) {
            return;
        }
        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(event.processId());
        if (processOpt.isEmpty()) {
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        // Detect by engine name (Nature-agnostic) so the trillian
        // default-alias and any future Nature recipes all trigger
        // cleanup.
        if (!TrillianSessionBootstrapper.CONTROL_ENGINE_NAME.equals(process.getThinkEngine())) {
            return;
        }
        Object userNameRaw = process.getEngineParams() == null
                ? null : process.getEngineParams().get(
                        TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        Object peerSessionRaw = process.getEngineParams() == null
                ? null : process.getEngineParams().get(
                        TrillianSessionBootstrapper.PARAM_PEER_SESSION_ID);

        // 1. Close the paired user-session via the standard cascade.
        //    SessionLifecycleService.closeWithCascade stops all
        //    processes in the session — the user-process and any
        //    worker children Trillian had spawned.
        if (peerSessionRaw instanceof String peerSessionId && !peerSessionId.isBlank()) {
            try {
                sessionLifecycleService.closeWithCascade(peerSessionId);
                log.info("Trillian cleanup: closed user-session '{}' (control id='{}')",
                        peerSessionId, event.processId());
            } catch (RuntimeException e) {
                log.warn("Trillian cleanup: user-session close '{}' failed: {}",
                        peerSessionId, e.toString());
            }
        } else {
            log.debug("Trillian control-process id='{}' closed without a peerSessionId — "
                    + "no user-session to cascade-close", event.processId());
        }

        // 2. Delete the bound service-account.
        if (userNameRaw instanceof String userName && !userName.isBlank()) {
            try {
                userService.delete(event.tenantId(), userName);
                log.info("Trillian cleanup: deleted service-account '{}' (tenant='{}')",
                        userName, event.tenantId());
            } catch (RuntimeException e) {
                log.warn("Trillian cleanup: delete of '{}' (tenant='{}') failed: {}",
                        userName, event.tenantId(), e.toString());
            }
        }
    }
}
