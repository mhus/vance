package de.mhus.vance.brain.bootstrap;

import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Drops every Eddie's persisted {@code mediation} state on Brain
 * startup. Mediation is live-wire state — a WS bound to a worker
 * session plus Eddie's LLM lane on hold — and carries no meaning
 * across a Brain restart: the WebSocket died with the old process and
 * never reconnects to "the same" mediation. The persistence is kept
 * so that successive lane-turns within one Brain lifetime can skip
 * the LLM call without reading per-turn state from somewhere else;
 * but the moment the Brain restarts those values are stale.
 *
 * <p>Without this hook, a Brain restart left every previously-mediated
 * Eddie permanently silenced — the LLM lane would refuse to run
 * because of the stale flag, and the user had no way to recover from
 * the UI (the {@code mediation-end} server-handler looks up Eddie via
 * the worker session id, which works only while the worker WS is
 * bound, not after a fresh client connect to Eddie's session).
 *
 * <p>Runs on {@link ApplicationReadyEvent} with
 * {@link Ordered#HIGHEST_PRECEDENCE} so it lands before any lane is
 * scheduled. Logs the count for diagnostics — a non-zero value on
 * every boot is normal after a crash mid-mediation.
 *
 * <p>Spec: {@code specification/eddie-engine.md} §8.5 — "Client-
 * Disconnect während aktiver Mediation: standardmäßig zurück zu Eddie,
 * Mediation-State wird auto-cleared." Brain restart is the strongest
 * form of disconnect.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediationStartupCleanup {

    private final ThinkProcessService thinkProcessService;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void clearStaleMediations() {
        long cleared = thinkProcessService.clearAllMediations();
        if (cleared > 0) {
            log.info("MediationStartupCleanup: cleared {} stale mediation record(s)", cleared);
        } else {
            log.debug("MediationStartupCleanup: no stale mediation records");
        }
    }
}
