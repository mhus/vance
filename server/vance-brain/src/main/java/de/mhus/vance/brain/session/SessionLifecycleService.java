package de.mhus.vance.brain.session;

import de.mhus.vance.api.session.DisconnectPolicy;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SuspendCause;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
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
 * cascade, archive cascade, close cascade, disconnect dispatch,
 * forced suspend handling.
 *
 * <p>See {@code specification/session-lifecycle.md} §3, §6, §8, §11.
 *
 * <p>The service is the only allowed entry point for transitioning a
 * session to {@code SUSPENDED}, {@code ARCHIVED} or {@code CLOSED}.
 * Direct calls to {@code SessionService.close()} are permitted only
 * from inside this service or from the {@code RestoreFromSuspendOnce}
 * test path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionLifecycleService {

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final EngineMessageService engineMessageService;
    /**
     * Lazy — {@link ThinkEngineService} pulls in tools/recipes that
     * transitively reach this service in the bean graph; an eager
     * dependency closes the cycle.
     */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    /**
     * Lazy — {@link SessionChatBootstrapper} depends on
     * {@code ThinkEngineService} too. Used only by {@code reactivateFromArchive}.
     */
    private final ObjectProvider<SessionChatBootstrapper> chatBootstrapperProvider;
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
                || session.getStatus() == SessionStatus.ARCHIVED
                || session.getStatus() == SessionStatus.SUSPENDED) {
            return;
        }
        DisconnectPolicy policy = session.getOnDisconnect();
        if (policy == null) policy = DisconnectPolicy.KEEP_OPEN;
        switch (policy) {
            case SUSPEND -> suspendCascade(sessionId, SuspendCause.DISCONNECT);
            case CLOSE -> closeWithCascade(sessionId, CloseReason.STOPPED);
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
     * <p>{@code FORCED} is the override case — {@code transitionAt} is
     * computed from {@code forcedFloorMs} regardless of the session's
     * {@code onSuspend} policy, see
     * {@code specification/session-lifecycle.md} §9.
     */
    public void suspendCascade(String sessionId, SuspendCause cause) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        if (session.getStatus() == SessionStatus.CLOSED
                || session.getStatus() == SessionStatus.ARCHIVED
                || session.getStatus() == SessionStatus.SUSPENDED) {
            return;
        }
        log.info("Suspend cascade sessionId='{}' cause={}", sessionId, cause);
        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
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
     * Backwards-compatible overload for legacy callers that did not
     * thread through a {@link CloseReason}. Treats the close as a
     * {@code STOPPED} cascade (logout / direct stop).
     */
    public void closeWithCascade(String sessionId) {
        closeWithCascade(sessionId, CloseReason.STOPPED);
    }

    /**
     * Close-cascade: every non-terminal engine receives {@code engine.stop(...)}
     * on its lane. Once all engines are {@code CLOSED}, the session
     * document is flipped to {@code CLOSED}.
     *
     * <p>{@code reason} is the audit reason stamped on every closed
     * process: {@link CloseReason#STOPPED} for logout, user-stop, or
     * disconnect-CLOSE; {@link CloseReason#AUTO_CLOSE} for the
     * {@code onSuspend=CLOSE} sweeper path; {@link CloseReason#ABANDONED}
     * for the abandoned-detection sweep path;
     * {@link CloseReason#USER_DELETE} for the hard-delete endpoint.
     */
    public void closeWithCascade(String sessionId, CloseReason reason) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        if (session.getStatus() == SessionStatus.CLOSED) return;
        log.info("Close cascade sessionId='{}' reason={}", sessionId, reason);
        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<String> closedProcessIds = new ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            closedProcessIds.add(p.getId());
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
        // Engines closed with reason=STOPPED — re-stamp to the cascade's
        // audit reason for everything that actually went through stop.
        // closeProcess is idempotent, but our overrideCloseReason only
        // rewrites the STOPPED case so DONE/STALE survives.
        if (reason != CloseReason.STOPPED) {
            for (String id : closedProcessIds) {
                thinkProcessService.overrideCloseReason(id, reason);
            }
        }
        // Drop pending engine messages — the session is going terminal.
        engineMessageService.purgeForProcesses(closedProcessIds);
        sessionService.close(sessionId);
    }

    /**
     * Archive-cascade: stop every non-CLOSED engine ({@code closeReason=ARCHIVED}),
     * purge the engine-message inbox, then flip the session to
     * {@link SessionStatus#ARCHIVED}. Conversation history in
     * {@code chat_messages} is left in place — it is the substance of
     * the archive.
     *
     * <p>Idempotent — re-archiving a session already in ARCHIVED is a
     * no-op. See {@code specification/session-lifecycle.md} §11.1.
     */
    public void archiveWithCascade(String sessionId) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        if (session.getStatus() == SessionStatus.ARCHIVED
                || session.getStatus() == SessionStatus.CLOSED) {
            return;
        }
        log.info("Archive cascade sessionId='{}'", sessionId);
        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<String> closedProcessIds = new ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            closedProcessIds.add(p.getId());
            if (p.getStatus() == ThinkProcessStatus.CLOSED) continue;
            futures.add(laneScheduler.submit(p.getId(), () -> {
                try {
                    engines.stop(p);
                } catch (RuntimeException e) {
                    log.warn("engine.stop failed during archive cascade id='{}': {}",
                            p.getId(), e.toString());
                    thinkProcessService.closeProcess(p.getId(), CloseReason.STOPPED);
                }
                return null;
            }));
        }
        joinAll(futures);
        for (String id : closedProcessIds) {
            thinkProcessService.overrideCloseReason(id, CloseReason.ARCHIVED);
        }
        engineMessageService.purgeForProcesses(closedProcessIds);
        sessionService.archive(sessionId);
    }

    /**
     * Reactivates an {@link SessionStatus#ARCHIVED} session: flips it back
     * to {@code IDLE}, renames the old chat-process so its name slot is
     * free, clears the {@code chatProcessId} link, and spawns a fresh
     * chat-process via the bootstrapper. The new engine sees an empty
     * conversation context by default — engine-specific replay of the
     * archived {@code ChatMessageDocument} history is the engine's
     * concern (see {@code specification/session-lifecycle.md} §11.2).
     *
     * <p>Throws {@link IllegalStateException} when called on a session
     * that is not {@code ARCHIVED}.
     */
    public void reactivateFromArchive(String sessionId) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) {
            throw new IllegalStateException("Session not found: " + sessionId);
        }
        if (session.getStatus() != SessionStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Session is not ARCHIVED: " + sessionId + " status=" + session.getStatus());
        }
        log.info("Reactivate session sessionId='{}'", sessionId);

        // Rename the old chat-process so the "chat" name slot is free
        // for the fresh spawn. Old conversation history in chat_messages
        // remains queryable by sessionId; the renamed process keeps its
        // CLOSED status with closeReason=ARCHIVED.
        String oldChatProcessId = session.getChatProcessId();
        if (oldChatProcessId != null) {
            String archivedName = SessionChatBootstrapper.CHAT_PROCESS_NAME
                    + "_archived_"
                    + (session.getArchivedAt() == null
                            ? java.time.Instant.now().toEpochMilli()
                            : session.getArchivedAt().toEpochMilli());
            thinkProcessService.renameClosedProcess(oldChatProcessId, archivedName);
        }
        sessionService.replaceChatProcessId(sessionId, null);

        // Status: ARCHIVED → IDLE.
        sessionService.reactivate(sessionId);

        // Spawn the new chat-process.
        SessionDocument refreshed = sessionService.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Session disappeared mid-reactivate: " + sessionId));
        chatBootstrapperProvider.getObject().ensureChatProcess(refreshed);
    }

    /**
     * Hard-delete a session: archive cascade (if not already archived
     * or closed) → close cascade with {@code reason=USER_DELETE} → drop
     * chat messages, processes, then the session document itself.
     *
     * <p>UI confirm-prompt is the caller's responsibility — this method
     * does not undo.
     */
    public void deleteSession(String sessionId) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        log.info("Hard-delete session sessionId='{}'", sessionId);
        if (session.getStatus() != SessionStatus.CLOSED
                && session.getStatus() != SessionStatus.ARCHIVED) {
            // Drive through the close cascade so engines see stop and
            // the per-process audit reason is correct.
            closeWithCascade(sessionId, CloseReason.USER_DELETE);
        } else if (session.getStatus() == SessionStatus.ARCHIVED) {
            // Already archived — engines are CLOSED with reason=ARCHIVED;
            // rewrite reason to USER_DELETE for the audit trail.
            List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                    session.getTenantId(), sessionId);
            for (ThinkProcessDocument p : processes) {
                thinkProcessService.overrideCloseReason(p.getId(), CloseReason.USER_DELETE);
            }
        }
        // Hard-delete the dependent collections, then the session row.
        chatMessageService.deleteBySession(session.getTenantId(), sessionId);
        thinkProcessService.deleteBySession(session.getTenantId(), sessionId);
        sessionService.delete(sessionId);
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
    public List<String> pauseActiveInSession(String sessionId) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return List.of();

        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        List<String> pausedNames = new ArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            ThinkProcessStatus s = p.getStatus();
            if (s == ThinkProcessStatus.CLOSED
                    || s == ThinkProcessStatus.PAUSED) {
                continue;
            }
            pausedNames.add(p.getName());
            // Set the out-of-band halt flag IMMEDIATELY (before queuing
            // the lane task) so engines whose runTurn drain-loops can
            // see it and bail out — otherwise their drain would keep
            // gobbling new pendings and the queued pause-task would
            // never get to fire on a busy lane.
            thinkProcessService.requestHalt(p.getId());
            futures.add(laneScheduler.submit(p.getId(), () -> {
                thinkProcessService.updateStatus(p.getId(), ThinkProcessStatus.PAUSED);
                thinkProcessService.clearHalt(p.getId());
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
                thinkProcessService.clearHalt(process.getId());
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
     * The hard counterpart to {@link #pauseActiveInSession}: workers
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
    public List<String> stopChildrenOfChat(String sessionId) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return List.of();
        String chatProcessId = session.getChatProcessId();
        if (chatProcessId == null) return List.of();

        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        List<String> stoppedNames = new ArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
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
