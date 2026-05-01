package de.mhus.vance.brain.session;

import de.mhus.vance.api.session.DisconnectPolicy;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SuspendCause;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Centralised lifecycle transitions on the session level — suspend
 * cascade, close cascade, disconnect dispatch, forced suspend handling.
 *
 * <p>See {@code specification/session-lifecycle.md} §3, §6, §8.
 *
 * <p>The service is the only allowed entry point for transitioning a
 * session to {@code SUSPENDED} or {@code CLOSED}. Direct calls to
 * {@code SessionService.close()} are permitted only from inside this
 * service or from the {@code RestoreFromSuspendOnce} test path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionLifecycleService {

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    /**
     * Lazy — {@link ThinkEngineService} pulls in tools/recipes that
     * transitively reach this service in the bean graph; an eager
     * dependency closes the cycle.
     */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final LaneScheduler laneScheduler;

    /**
     * System-wide minimum keep-duration after a {@link SuspendCause#FORCED}
     * suspend. Recipe-author cannot lower this — it's a safety floor that
     * gives an operator a chance to intervene after pod-shutdown / lease-loss
     * during running work. See {@code specification/session-lifecycle.md} §9.
     */
    @Value("${vance.session.forced-floor-ms:604800000}") // 7 days default
    private long forcedFloorMs;

    /**
     * Dispatch on the session's {@link DisconnectPolicy} when its bound
     * client connection went away. Called by
     * {@code VanceWebSocketHandler.afterConnectionClosed} after the
     * connection-side cleanup (registries, unbind) has run.
     */
    public void onDisconnect(String sessionId) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        if (session.getStatus() == SessionStatus.CLOSED
                || session.getStatus() == SessionStatus.SUSPENDED) {
            return;
        }
        DisconnectPolicy policy = session.getOnDisconnect();
        if (policy == null) policy = DisconnectPolicy.KEEP_OPEN;
        switch (policy) {
            case SUSPEND -> suspendCascade(sessionId, SuspendCause.DISCONNECT);
            case CLOSE -> closeWithCascade(sessionId);
            case KEEP_OPEN -> {
                // Engines run on; idle-detection or explicit stop will
                // catch this session later.
            }
        }
    }

    /**
     * Suspend-cascade: every non-terminal engine in the session receives
     * {@code engine.suspend(...)} on its own lane (parallel across
     * engines, serial per engine). Once all engines are
     * {@code SUSPENDED}, the session document is flipped to
     * {@code SUSPENDED} with the given {@link SuspendCause}.
     *
     * <p>{@code FORCED} is the override case — {@code deleteAt} is
     * computed from {@code forcedFloorMs} regardless of the session's
     * {@code onSuspend} policy, see
     * {@code specification/session-lifecycle.md} §9.
     */
    public void suspendCascade(String sessionId, SuspendCause cause) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        if (session.getStatus() == SessionStatus.CLOSED
                || session.getStatus() == SessionStatus.SUSPENDED) {
            return;
        }
        log.info("Suspend cascade sessionId='{}' cause={}", sessionId, cause);
        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            if (p.getStatus() == ThinkProcessStatus.CLOSED
                    || p.getStatus() == ThinkProcessStatus.SUSPENDED) {
                continue;
            }
            futures.add(laneScheduler.submit(p.getId(), () -> {
                try {
                    engines.suspend(p);
                } catch (RuntimeException e) {
                    log.warn("engine.suspend failed during cascade id='{}': {}",
                            p.getId(), e.toString());
                    thinkProcessService.updateStatus(
                            p.getId(), ThinkProcessStatus.SUSPENDED);
                }
                return null;
            }));
        }
        joinAll(futures);
        sessionService.suspend(sessionId, cause, forcedFloorMs);
    }

    /**
     * Close-cascade: every non-terminal engine receives {@code engine.stop(...)}
     * on its lane. Once all engines are {@code CLOSED}, the session document
     * is flipped to {@code CLOSED}. Used by the logout path, the suspend
     * sweeper (deleteAt-driven), and event-driven session auto-close.
     */
    public void closeWithCascade(String sessionId) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        if (session.getStatus() == SessionStatus.CLOSED) return;
        log.info("Close cascade sessionId='{}'", sessionId);
        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            if (p.getStatus() == ThinkProcessStatus.CLOSED) continue;
            futures.add(laneScheduler.submit(p.getId(), () -> {
                try {
                    engines.stop(p);
                } catch (RuntimeException e) {
                    log.warn("engine.stop failed during cascade id='{}': {}",
                            p.getId(), e.toString());
                    thinkProcessService.closeProcess(p.getId(), CloseReason.STOPPED);
                }
                return null;
            }));
        }
        joinAll(futures);
        sessionService.close(sessionId);
    }

    /**
     * Pause every non-CLOSED process in the session — that's the
     * chat-process plus all its children. Used by the foot ESC
     * binding and {@code /pause} command: "halt activity so I can
     * redirect". The chat itself goes PAUSED too so the user's next
     * typed message arrives at a stopped engine; the
     * {@code process-steer} WS handler auto-resumes the target on
     * inbound user input, so the user sees the next chat round-trip
     * naturally pick up the correction.
     *
     * <p>Pause runs on each process's lane and serialises with any
     * in-flight {@code runTurn}. The status transition to
     * {@code PAUSED} happens at the next safe boundary — current
     * LLM call (if any) finishes first.
     *
     * @return the names of the processes that were paused (empty
     *         when nothing was active)
     */
    public java.util.List<String> pauseActiveInSession(String sessionId) {
        de.mhus.vance.shared.session.SessionDocument session =
                sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return java.util.List.of();

        java.util.List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        java.util.List<String> pausedNames = new java.util.ArrayList<>();
        java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            ThinkProcessStatus s = p.getStatus();
            if (s == ThinkProcessStatus.CLOSED
                    || s == ThinkProcessStatus.PAUSED) {
                continue;
            }
            pausedNames.add(p.getName());
            futures.add(laneScheduler.submit(p.getId(), () -> {
                thinkProcessService.updateStatus(p.getId(), ThinkProcessStatus.PAUSED);
                return null;
            }));
        }
        joinAll(futures);
        log.info("Paused {} process(es) in session='{}': {}",
                pausedNames.size(), sessionId, pausedNames);
        return pausedNames;
    }

    /**
     * Resume a previously paused process: status PAUSED → IDLE on the
     * lane, then a {@code runTurn} is scheduled so any pending
     * messages that piled up while paused get drained.
     */
    public void resumeProcess(ThinkProcessDocument process,
                              ProcessEventEmitter eventEmitter) {
        try {
            laneScheduler.submit(process.getId(), () -> {
                if (process.getStatus() == ThinkProcessStatus.PAUSED
                        || process.getStatus() == ThinkProcessStatus.SUSPENDED) {
                    thinkProcessService.updateStatus(
                            process.getId(), ThinkProcessStatus.IDLE);
                }
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted resuming process", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new IllegalStateException(
                    "resume failed: " + cause.getMessage(), cause);
        }
        // Drain any pending that piled up while paused. scheduleTurn is a
        // no-op if status isn't drainable (handled inside ProcessEventEmitter).
        eventEmitter.scheduleTurn(process.getId());
    }

    /**
     * Stop every non-CLOSED <em>child</em> of the session's chat-process.
     * The hard counterpart to {@link #pauseChildrenOfChat}: workers
     * receive {@code engine.stop} on their lanes and transition to
     * {@code CLOSED} with {@code closeReason=STOPPED}. Chat-process
     * itself is never closed by this — use the close-cascade
     * (logout) for that.
     *
     * <p>Used by the foot {@code /stop} command — "abandon the
     * current direction, start fresh". Arthur sees the resulting
     * STOPPED parent-notifications and decides whether to spawn
     * something new.
     *
     * @return the names of the processes that were stopped (empty
     *         when no active workers existed)
     */
    public java.util.List<String> stopChildrenOfChat(String sessionId) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return java.util.List.of();
        String chatProcessId = session.getChatProcessId();
        if (chatProcessId == null) return java.util.List.of();

        java.util.List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        java.util.List<String> stoppedNames = new java.util.ArrayList<>();
        java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            if (!chatProcessId.equals(p.getParentProcessId())) continue;
            if (p.getStatus() == ThinkProcessStatus.CLOSED) continue;
            stoppedNames.add(p.getName());
            futures.add(laneScheduler.submit(p.getId(), () -> {
                try {
                    engines.stop(p);
                } catch (RuntimeException e) {
                    log.warn("engine.stop failed on cascade child id='{}': {}",
                            p.getId(), e.toString());
                    thinkProcessService.closeProcess(p.getId(), CloseReason.STOPPED);
                }
                return null;
            }));
        }
        joinAll(futures);
        log.info("Stopped {} worker(s) under chat-process of session='{}': {}",
                stoppedNames.size(), sessionId, stoppedNames);
        return stoppedNames;
    }

    /**
     * Stop a single process on its lane (user-driven WS process-stop).
     * Returns when the lane has finished the {@code engine.stop} call.
     */
    public void stopProcess(ThinkProcessDocument process) {
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        try {
            laneScheduler.submit(process.getId(), () -> {
                try {
                    engines.stop(process);
                } catch (RuntimeException e) {
                    log.warn("engine.stop failed for process id='{}': {}",
                            process.getId(), e.toString());
                    thinkProcessService.closeProcess(
                            process.getId(), CloseReason.STOPPED);
                }
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted waiting for engine.stop", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new IllegalStateException(
                    "engine.stop failed: " + cause.getMessage(), cause);
        }
    }

    private static void joinAll(List<CompletableFuture<Void>> futures) {
        for (CompletableFuture<Void> f : futures) {
            try {
                f.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException ee) {
                // Already logged at the lane callback's catch.
            }
        }
    }
}
