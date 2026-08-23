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
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.sessiongroup.SessionGroupService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
    private final MemoryService memoryService;
    private final SessionGroupService sessionGroupService;
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
     * Subsystems that follow a session's lifetime. Empty by default —
     * nothing in the core needs one; Trillian is the first because its
     * worker lives in a second session.
     */
    private final java.util.List<SessionLifecycleHook> lifecycleHooks;

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
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        // Drain with re-scan: a child think-process spawned by an in-flight turn
        // AFTER the initial snapshot but before the terminal flip would otherwise
        // escape the cascade (running process outside a SUSPENDED session). The
        // loop re-scans until no new non-terminal process appears.
        List<String> laneIdsToForget = drainSessionProcesses(
                session.getTenantId(), sessionId, p -> {
                    if (p.getStatus() == ThinkProcessStatus.SUSPENDED) {
                        return null; // already suspended — only its lane needs forgetting
                    }
                    return laneScheduler.submit(p.getId(), () -> {
                        try {
                            engines.suspend(p);
                        } catch (RuntimeException e) {
                            log.warn("engine.suspend failed during cascade id='{}': {}",
                                    p.getId(), e.toString());
                            thinkProcessService.updateStatus(
                                    p.getId(), ThinkProcessStatus.SUSPENDED);
                        }
                        return null;
                    });
                });
        // Memory cleanup: drop each suspended process's lane and per-engine
        // state so a SUSPENDED session lives only in MongoDB. The lane map
        // would otherwise grow monotonically over the pod's lifetime.
        // Lanes are lazily re-created on the next submit (e.g. on resume).
        for (String id : laneIdsToForget) {
            laneScheduler.forget(id);
        }
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
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        // Drain with re-scan (see suspendCascade) so a mid-cascade-spawned child
        // is stopped too rather than escaping into a CLOSED session.
        List<String> closedProcessIds = drainSessionProcesses(
                session.getTenantId(), sessionId, p ->
                        laneScheduler.submit(p.getId(), () -> {
                            try {
                                engines.stop(p);
                            } catch (RuntimeException e) {
                                log.warn("engine.stop failed during cascade id='{}': {}",
                                        p.getId(), e.toString());
                                thinkProcessService.closeProcess(p.getId(), CloseReason.STOPPED);
                            }
                            return null;
                        }));
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
        fireHooks("closed", session, SessionLifecycleHook::onSessionClosed);
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
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        // Drain with re-scan (see suspendCascade) so a mid-cascade-spawned child
        // is stopped too rather than escaping into an ARCHIVED session.
        List<String> closedProcessIds = drainSessionProcesses(
                session.getTenantId(), sessionId, p ->
                        laneScheduler.submit(p.getId(), () -> {
                            try {
                                engines.stop(p);
                            } catch (RuntimeException e) {
                                log.warn("engine.stop failed during archive cascade id='{}': {}",
                                        p.getId(), e.toString());
                                thinkProcessService.closeProcess(p.getId(), CloseReason.STOPPED);
                            }
                            return null;
                        }));
        for (String id : closedProcessIds) {
            thinkProcessService.overrideCloseReason(id, CloseReason.ARCHIVED);
        }
        engineMessageService.purgeForProcesses(closedProcessIds);
        sessionService.archive(sessionId);
        fireHooks("archived", session, SessionLifecycleHook::onSessionArchived);
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
        // Carry the recipe over. Without it the fresh spawn falls back to
        // the tenant default, so reactivating silently turned a session
        // into an Arthur one — observed on a Trillian session, where the
        // pairing then never happened because the bootstrap keys on the
        // control engine. Every non-default recipe was affected; only
        // Arthur sessions could not tell.
        String previousRecipe = oldChatProcessId == null ? null
                : thinkProcessService.findById(oldChatProcessId)
                        .map(ThinkProcessDocument::getRecipeName)
                        .orElse(null);
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

        // Before the spawn: the fresh chat-process bootstrap may want to
        // pick up whatever a hook prepares here.
        fireHooks("unarchived", session, SessionLifecycleHook::onSessionUnarchived);

        // Spawn the new chat-process.
        SessionDocument refreshed = sessionService.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Session disappeared mid-reactivate: " + sessionId));
        chatBootstrapperProvider.getObject()
                .ensureChatProcess(refreshed, /*parentProcessId*/ null, previousRecipe);
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
        // Hooks first: whatever hangs off this session is usually linked
        // through a process's engineParams, and those rows are about to
        // go away.
        fireHooks("deleted", session, SessionLifecycleHook::onSessionDeleted);
        // Hard-delete the dependent collections, then the session row.
        // Memory + group cleanup share the semantics of the session-move
        // path (see planning/session-move.md §8) — both had been leaking
        // session-scoped memories and orphaned group memberships.
        chatMessageService.deleteBySession(session.getTenantId(), sessionId);
        thinkProcessService.deleteBySession(session.getTenantId(), sessionId);
        memoryService.deleteBySession(session.getTenantId(), sessionId);
        sessionGroupService.removeSessionFromProject(
                session.getTenantId(), session.getProjectId(), sessionId);
        sessionService.delete(sessionId);
    }


    /**
     * Runs every {@link SessionLifecycleHook} for one transition.
     *
     * <p>A throwing hook is logged and skipped: the transition the user
     * asked for has to happen regardless of whether some subsystem
     * managed to follow it. A hook that fails leaves its own mess, not a
     * half-archived session.
     */
    private void fireHooks(String transition, SessionDocument session,
            java.util.function.BiConsumer<SessionLifecycleHook, SessionDocument> call) {
        for (SessionLifecycleHook hook : lifecycleHooks) {
            try {
                call.accept(hook, session);
            } catch (RuntimeException e) {
                log.warn("Session-lifecycle hook {} failed on {} for session '{}': {}",
                        hook.getClass().getSimpleName(), transition,
                        session.getSessionId(), e.toString(), e);
            }
        }
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
            if (!isInterruptible(p)) {
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
     * Whether a user-driven pause has anything to interrupt on this
     * process — {@code true} only for {@code RUNNING} (mid-turn) and
     * {@code INIT} (spawned, first turn queued).
     *
     * <p>This is the authoritative answer for the "was anything actually
     * running?" question that clients must not try to answer themselves:
     * foot sends the ESC pause unconditionally (its busy counter is a
     * reconstruction from turn-boundary pings and goes stale on a
     * reconnect), so the filter has to sit here. Everything else is
     * deliberately left alone:
     * <ul>
     *   <li>{@code IDLE} — nothing to halt, and flipping it to
     *       {@code PAUSED} would mint a bogus "USER INTERRUPTED —
     *       RECONSIDER" preamble on the user's next message
     *       ({@code ProcessSteerHandler#buildResumeContext}).</li>
     *   <li>{@code BLOCKED} — waiting on an answer (inbox / delegation);
     *       pausing would strand the party that owes the reply. Use
     *       {@code /stop} to end it.</li>
     *   <li>{@code PAUSED} / {@code SUSPENDED} / {@code CLOSED} — already
     *       halted.</li>
     * </ul>
     */
    public static boolean isInterruptible(ThinkProcessDocument process) {
        ThinkProcessStatus s = process.getStatus();
        return s == ThinkProcessStatus.RUNNING || s == ThinkProcessStatus.INIT;
    }

    /**
     * Session-level resume cascade — the symmetric counterpart to
     * {@link #suspendCascade(String, SuspendCause)}. For every
     * {@code SUSPENDED} engine on the session, calls
     * {@code engine.resume(process, ctx)} on its lane; once the lanes
     * land, the session document flips back from {@code SUSPENDED} to
     * {@code IDLE} and the runtime-suspend fields are cleared. Pending
     * messages that accumulated while suspended get drained via
     * {@link ProcessEventEmitter#scheduleTurn(String)} on each
     * non-closed process.
     *
     * <p>Idempotent: a no-op when the session is {@code CLOSED} or
     * {@code ARCHIVED}, and skips engines that aren't actually
     * {@code SUSPENDED} (so calling on a fully-IDLE session is safe).
     *
     * <p>Generic over the suspend cause — handles {@code IDLE},
     * {@code DISCONNECT}, and {@code FORCED} suspends from a single
     * code path, per {@code specification/session-lifecycle.md} §10.2.
     *
     * <p>Callers — the WS {@code session-resume} handler (on
     * reconnect) and the REST {@code POST /sessions/{id}/resume}
     * endpoint — pass in their own {@link ProcessEventEmitter}
     * because the bean graph wouldn't let the lifecycle service take
     * a direct dependency on the emitter without a cycle.
     */
    public void resumeSessionCascade(String sessionId,
                                     ProcessEventEmitter eventEmitter) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        if (session.getStatus() == SessionStatus.CLOSED
                || session.getStatus() == SessionStatus.ARCHIVED) {
            return;
        }
        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(
                session.getTenantId(), sessionId);
        boolean anySuspended = false;
        for (ThinkProcessDocument p : processes) {
            if (p.getStatus() == ThinkProcessStatus.SUSPENDED) {
                anySuspended = true;
                break;
            }
        }
        if (!anySuspended && session.getStatus() != SessionStatus.SUSPENDED) {
            // Nothing to resume — neither session nor any engine is
            // suspended. Skip the noise.
            return;
        }
        log.info("Resume cascade sessionId='{}' (sessionStatus={})",
                sessionId, session.getStatus());
        ThinkEngineService engines = thinkEngineServiceProvider.getObject();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            if (p.getStatus() != ThinkProcessStatus.SUSPENDED) continue;
            futures.add(laneScheduler.submit(p.getId(), () -> {
                try {
                    engines.resume(p);
                } catch (RuntimeException e) {
                    log.warn("engine.resume failed during cascade id='{}': {}",
                            p.getId(), e.toString());
                    // Best-effort fallback: at least lift the status
                    // off SUSPENDED so the lane can run again.
                    thinkProcessService.updateStatus(
                            p.getId(), ThinkProcessStatus.IDLE);
                }
                return null;
            }));
        }
        joinAll(futures);
        // Session document: SUSPENDED → IDLE, clear runtime fields.
        // {@link SessionService#resume} is itself idempotent — a no-op
        // when the session wasn't SUSPENDED to begin with (e.g. only
        // a single FORCED-suspended engine without session-level
        // suspend, possible at pod-shutdown per spec §10).
        sessionService.resume(sessionId);
        // Drain any pending that piled up while the engines were down.
        // Scheduling a turn on a CLOSED process is a no-op inside
        // ProcessEventEmitter; iterate over the original list to keep
        // the call set deterministic.
        for (ThinkProcessDocument p : processes) {
            if (p.getStatus() == ThinkProcessStatus.CLOSED) continue;
            eventEmitter.scheduleTurn(p.getId());
        }
    }

    /**
     * How long a caller waits for the pause to land on the lane before
     * returning without the confirmation. The lane task stays queued
     * either way — the cap is only about how long a request thread stays
     * bound to a lane that is busy with a model call.
     */
    private static final long PAUSE_WAIT_SECONDS = 10;

    /**
     * Pause a single process — the named-process counterpart of
     * {@link #pauseActiveInSession(String)}, and built the same way.
     *
     * <p>Two channels, because one of them is always too late: the status
     * transition runs on the process's lane so it cannot land in the
     * middle of a turn, but a lane task cannot run while that turn holds
     * the lane. So the out-of-band halt flag goes out first — that is what
     * an engine's loop head reads (see {@code OrchestratorInterrupt}), and
     * what makes the pause take effect inside the current turn rather than
     * after it.
     *
     * <p>The wait for the lane is capped at {@link #PAUSE_WAIT_SECONDS}:
     * callers reach this from a WebSocket receive thread, and an
     * unbounded wait binds that thread for the length of whatever the
     * engine is doing. A timeout is not a failure — the write is queued
     * and the halt flag is already set.
     *
     * <p>Only {@linkplain #isInterruptible interruptible} processes are
     * touched, the same filter the session-wide pause uses: an IDLE
     * process has nothing to halt and flipping it to PAUSED would mint a
     * bogus "USER INTERRUPTED — RECONSIDER" preamble on the user's next
     * message; a BLOCKED one is owed an answer and pausing it strands the
     * party that owes it.
     *
     * @return {@code true} when this call did the pausing
     */
    public boolean pauseProcess(ThinkProcessDocument process) {
        if (!isInterruptible(process)) {
            log.debug("pauseProcess id='{}' skipped — status {} has nothing to interrupt",
                    process.getId(), process.getStatus());
            return false;
        }
        thinkProcessService.requestHalt(process.getId());
        CompletableFuture<Void> landed = laneScheduler.submit(process.getId(), () -> {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.PAUSED);
            thinkProcessService.clearHalt(process.getId());
            return null;
        });
        try {
            landed.get(PAUSE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while pausing " + process.getId(), ie);
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("pauseProcess id='{}' — lane busy, PAUSED stays queued (halt flag is set)",
                    process.getId());
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new IllegalStateException("Pause failed: " + cause.getMessage(), cause);
        }
        return true;
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

    /** Round cap for the cascade drain loop — a backstop against a runaway spawner. */
    private static final int MAX_CASCADE_ROUNDS = 8;

    /**
     * Applies {@code action} to every non-CLOSED think-process of the session,
     * re-scanning after each round until a scan surfaces no new non-terminal
     * process (stable) or {@link #MAX_CASCADE_ROUNDS} is hit.
     *
     * <p>Closes the mid-cascade-spawn window: the lifecycle cascades used to
     * snapshot the process set once, submit their engine actions, join, then
     * flip the session terminal. A child spawned by an in-flight turn (which is
     * serialized on a lane and thus runs after the snapshot) escaped — a running
     * process outside a SUSPENDED/CLOSED/ARCHIVED session. Re-scanning catches it
     * before the flip.
     *
     * @param action submits the per-process engine action and returns its future,
     *               or {@code null} to record the id without an action (e.g. an
     *               already-SUSPENDED process that only needs its lane forgotten)
     * @return the ids of every non-CLOSED process handled, across all rounds
     */
    private List<String> drainSessionProcesses(
            String tenantId, String sessionId,
            java.util.function.Function<ThinkProcessDocument, CompletableFuture<Void>> action) {
        java.util.LinkedHashSet<String> handled = new java.util.LinkedHashSet<>();
        boolean stable = false;
        int rounds = 0;
        while (rounds++ < MAX_CASCADE_ROUNDS) {
            List<ThinkProcessDocument> processes =
                    thinkProcessService.findBySession(tenantId, sessionId);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            boolean sawNew = false;
            for (ThinkProcessDocument p : processes) {
                if (p.getStatus() == ThinkProcessStatus.CLOSED) continue;
                if (!handled.add(p.getId())) continue;
                sawNew = true;
                CompletableFuture<Void> f = action.apply(p);
                if (f != null) futures.add(f);
            }
            if (!sawNew) {
                stable = true;
                break;
            }
            joinAll(futures);
        }
        if (!stable) {
            log.warn("Session cascade '{}' hit the {}-round drain cap — a process may still "
                    + "be spawning children; proceeding to the terminal flip", sessionId,
                    MAX_CASCADE_ROUNDS);
        }
        return new ArrayList<>(handled);
    }
}
