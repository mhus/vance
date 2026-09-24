package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.brain.hactar.phases.ExecutingPhase;
import de.mhus.vance.brain.hactar.phases.LoadingPhase;
import de.mhus.vance.brain.hactar.phases.ValidatingPhase;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Hactar's mechanical run runner — the analog of
 * {@code WowbaggerPoolService} (planning/hactar-agent-identity.md §3.1): one
 * background job per kick drives the phase machine
 * READY → LOADING → [VALIDATING] → EXECUTING → DONE/FAILED off the engine
 * lane, so a session-mode agent stays free to answer mid-run steers.
 *
 * <p><b>The lane is never blocked by a phase.</b> The engine's session-mode
 * {@code runTurn} is a pure chat turn; every phase transition persists the
 * state and wakes the agent (chat-history note + pending message — the
 * Wowbagger wakeup pattern). The agent never executes a phase itself — not
 * even "just a quick validate" (Agent über Mechanik, same rule as Wowbagger).
 *
 * <p><b>Terminal behavior follows the spawn form</b> (F1, decided): the
 * runner never closes the process itself — it wakes the engine, and the
 * engine's runTurn does the close dance for the worker form (with
 * {@code emitFinalReply}) and keeps the chat form open (re-arm).
 *
 * <p><b>Stop</b> (F2): interrupts the runner thread between phases or, mid
 * EXECUTING, through the executor's interrupt path ({@code ErrorClass.CANCELLED}
 * — the watchdog future is cancelled and the GraalJS context force-closed).
 * A cancelled script may leave partial side effects, exactly like a timeout;
 * the failure reason names it instead of hiding it.
 *
 * <p><b>Suspend:</b> a background script keeps running while its process is
 * SUSPENDED — the script is server-side work, the suspend only parks the
 * conversational lane. Pending wakeups accumulate and drain on resume. A
 * Brain restart kills the in-memory handle mid-run; the next resume detects
 * the orphaned state and marks it FAILED (no recovery — the agent offers a
 * fresh kick).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HactarRunService {

    /**
     * Sender identity the runner stamps on its wakeup pending messages. The
     * engine recognises wakeups by this sender and keeps them out of the
     * chat-log append — the runner writes its note to the history itself
     * (see {@link #wakeup}), so the drained copy must not double it.
     */
    public static final String WAKEUP_SENDER = "hactar-run";

    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final HactarStateStore stateStore;
    private final HactarProgressRing progressRing;
    private final HactarConsoleLog consoleLog;
    private final LoadingPhase loadingPhase;
    private final ValidatingPhase validatingPhase;
    private final ExecutingPhase executingPhase;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /** One live handle per kicked run (in-memory; dies with the pod). */
    static final class Handle {
        volatile Thread runner;
        volatile boolean running = true;
        volatile boolean stopRequested;
        volatile long startedAtMs = System.currentTimeMillis();

        boolean isStopRequested() {
            return stopRequested;
        }
    }

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hactar-run");
        t.setDaemon(true);
        return t;
    });

    // ──────────────────── Queries ────────────────────

    /** Whether a run is currently executing phases for the process. */
    public boolean isRunning(String processId) {
        Handle h = handles.get(processId);
        return h != null && h.running;
    }

    /** Epoch ms of the current kick — {@code null} when no run was kicked. */
    public Long runStartedAtMs(String processId) {
        Handle h = handles.get(processId);
        return h == null ? null : h.startedAtMs;
    }

    // ──────────────────── Kick / stop ────────────────────

    /**
     * Kicks a run: submits the phase machine to the background executor.
     * The state must carry a {@code scriptRef} (the caller — engine auto-kick
     * or {@code hactar_start} — validated the prerequisites; F2: no start
     * while a run is live).
     */
    public void start(ThinkProcessDocument process, HactarState state) {
        Handle handle = new Handle();
        Handle previous = handles.put(process.getId(), handle);
        if (previous != null && previous.running) {
            // Lost race against a concurrent kick — restore and refuse.
            handles.put(process.getId(), previous);
            throw new IllegalStateException("A run is already active for process " + process.getId());
        }
        state.setRunStartedAtMs(System.currentTimeMillis());
        handle.startedAtMs = state.getRunStartedAtMs();
        state.setStatus(HactarStatus.READY);
        // A fresh kick resets the per-run idempotency guard — the previous
        // run's reply was (or should have been) consumed long ago.
        state.setReplyEmitted(false);
        // ...and the per-run RESULT fields — a stale tail from a previous
        // (e.g. failed) run must not show up as the current run's output
        // (live finding: the status block quoted "Hello, world! #1" from
        // the run that died on a TypeError while the fresh run was mid-flight).
        state.setExecutionResult(null);
        state.setExecutionDurationMs(0);
        state.setExecutionError(null);
        state.setExecutionErrorClass(null);
        state.setFailureReason(null);
        state.setConsoleTail(null);
        stateStore.persist(process, state);
        progressRing.clear(process.getId());
        consoleLog.startRun(process.getId());
        kickNote(
                process,
                "run started — '" + state.getScriptRef() + "'"
                        + (state.isValidateBeforeRun() ? " (deep-validate before run)" : ""));
        executor.submit(() -> {
            Thread current = Thread.currentThread();
            handle.runner = current;
            current.setName("hactar-run-" + process.getId());
            try {
                runPhases(process, state, handle);
            } catch (RuntimeException e) {
                log.warn("Hactar run id='{}' runner crashed: {}", process.getId(), e.toString(), e);
                state.setStatus(HactarStatus.FAILED);
                state.setFailureReason(
                        handle.isStopRequested()
                                ? "stopped by request — the runner was interrupted mid-phase; "
                                        + "partial side effects of the interrupted script are possible; "
                                        + "a fresh start (hactar_start) begins a new run"
                                : "run runner crashed: " + e.getMessage());
                stateStore.persist(process, state);
                wakeup(process, "run failed: " + state.getFailureReason());
            } finally {
                consoleLog.finishRun(process.getId());
                handle.running = false;
                // Clear a possibly-set interrupt flag before the pooled
                // thread goes back to the pool — a stale flag would make
                // the NEXT task on this thread see a phantom interrupt.
                Thread.interrupted();
            }
        });
    }

    /**
     * Stops the live run (F2 prerequisite for a re-kick): sets the stop flag
     * and interrupts the runner thread. Between phases the loop exits
     * cleanly; mid-EXECUTING the executor's interrupt path cancels the
     * watchdog future and force-closes the GraalJS context. Idempotent —
     * stopping a run that already ended is a no-op returning
     * {@code false}.
     *
     * @return whether a live run was interrupted
     */
    public boolean stop(String processId) {
        Handle h = handles.get(processId);
        if (h == null || !h.running) {
            return false;
        }
        h.stopRequested = true;
        Thread runner = h.runner;
        if (runner != null) {
            runner.interrupt();
        }
        return true;
    }

    // ──────────────────── The phase machine ────────────────────

    /**
     * Drives LOADING → [VALIDATING] → EXECUTING on the runner thread,
     * persisting after every phase and waking the agent on terminal
     * transitions. Stop requests are honored between phases (clean) and via
     * interrupt inside EXECUTING (executor CANCELLED path).
     */
    private void runPhases(ThinkProcessDocument process, HactarState state, Handle handle) {
        while (true) {
            if (handle.isStopRequested()) {
                String before = state.getStatus().name();
                state.setStatus(HactarStatus.FAILED);
                state.setFailureReason("stopped by request before " + before);
                stateStore.persist(process, state);
                wakeup(
                        process,
                        "run stopped by request before " + before
                                + " — partial side effects of the interrupted script are possible; "
                                + "a fresh start (hactar_start) begins a new run");
                consoleLog.finishRun(process.getId());
                return;
            }
            HactarStatus next =
                    switch (state.getStatus()) {
                        case READY -> HactarStatus.LOADING;
                        case LOADING -> loadingPhase.execute(state, process);
                        case VALIDATING -> validatingPhase.execute(state, process);
                        case EXECUTING -> executingPhase.execute(state, process);
                        case DONE -> HactarStatus.DONE;
                        case FAILED -> HactarStatus.FAILED;
                    };
            if (next == HactarStatus.FAILED
                    && handle.isStopRequested()
                    && "CANCELLED".equals(state.getExecutionErrorClass())) {
                // The stop interrupted the script mid-run: the executor
                // mapped it to a CANCELLED ScriptExecutionException and the
                // phase returned FAILED — relabel it as a stop (the user
                // asked for it), not as a script defect. A run that
                // completed despite the stop keeps its truthful DONE.
                state.setFailureReason("stopped by request (script cancelled mid-run) — partial side effects "
                        + "are possible; a fresh start (hactar_start) begins a new run");
            }
            state.setStatus(next);
            stateStore.persist(process, state);
            if (next == HactarStatus.DONE) {
                wakeup(
                        process,
                        "run finished — '" + state.getScriptRef() + "' ("
                                + state.getExecutionDurationMs() + "ms). Return value:\n"
                                + renderValue(state) + consoleExcerpt(state));
                consoleLog.finishRun(process.getId());
                return;
            }
            if (next == HactarStatus.FAILED) {
                wakeup(
                        process,
                        "run failed: "
                                + (state.getFailureReason() == null ? "unknown reason" : state.getFailureReason())
                                + " — decide: spawn Slart mode=Update with the failure reason (script authoring), "
                                + "or start a different script" + consoleExcerpt(state));
                consoleLog.finishRun(process.getId());
                return;
            }
        }
    }
    /**
     * The console excerpt appended to terminal notes (the visibility fix
     * for "what did the script print?"): last 10 lines, 1.5 KB cap —
     * the full tail (up to 8 KB) stays on the state for the status block
     * and {@code //hactar}.
     */
    private static String consoleExcerpt(HactarState state) {
        String excerpt = ConsoleExcerpt.of(state.getConsoleTail(), 10, 1500);
        return excerpt.isEmpty() ? "" : "\n\nConsole output (tail):\n```\n" + excerpt + "\n```";
    }

    /**
     * Marks a mid-run state orphaned by a Brain restart: detected by the
     * engine on resume when the state claims LOADING/VALIDATING/EXECUTING
     * but no live handle exists. Terminal per the no-recovery contract — the
     * agent reports and offers a fresh kick.
     */
    public HactarState failOrphanedRun(ThinkProcessDocument process, HactarState state) {
        state.setStatus(HactarStatus.FAILED);
        state.setFailureReason("run interrupted (engine restart) — no live run handle; "
                + "restart with hactar_start if the work should run again");
        stateStore.persist(process, state);
        return state;
    }

    // ──────────────────── Wakeup ────────────────────

    /**
     * History-only kick note — NO pending message, deliberately: the kicker
     * (engine auto-kick or the agent's {@code hactar_start}) already knows;
     * a pending copy would fire an agent turn on a worker-form spawn right
     * after the kick, burning an LLM call nobody asked for. The note still
     * lands in the history so {@code process_history_text} forensics see
     * when the run began.
     */
    private void kickNote(ThinkProcessDocument process, String note) {
        try {
            chatMessageService.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("[run] " + note)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Hactar run id='{}' kick note failed: {}", process.getId(), e.toString());
        }
    }

    /**
     * Wakes the agent: a chat-history note (the vermerk, visible even before
     * the agent turn) plus a pending message that auto-wakes the agent's
     * lane. The pending copy is only the <b>trigger</b> — it carries
     * {@link #WAKEUP_SENDER}, and the engine skips its chat-log append for
     * that sender because the note above is already the history copy; both
     * halves together would otherwise show every wakeup twice in the
     * transcript and the LLM history.
     */
    private void wakeup(ThinkProcessDocument process, String note) {
        try {
            chatMessageService.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("[run] " + note)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Hactar run id='{}' chat note failed: {}", process.getId(), e.toString());
        }
        try {
            PendingMessageDocument message = new PendingMessageDocument();
            message.setContent("[run] " + note);
            message.setFromUser(WAKEUP_SENDER);
            thinkProcessService.appendPending(process.getId(), message, "");
        } catch (RuntimeException e) {
            log.warn("Hactar run id='{}' wakeup failed: {}", process.getId(), e.toString());
        }
    }

    private String renderValue(HactarState state) {
        Object value = state.getExecutionResult();
        if (value == null) {
            return "(no return value)";
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return "```json\n" + objectMapper.writeValueAsString(value) + "\n```";
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }
}
