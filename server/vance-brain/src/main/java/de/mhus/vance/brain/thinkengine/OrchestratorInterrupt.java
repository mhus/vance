package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;

/**
 * Mid-orchestration interrupt probe shared by the worker-driving engines
 * (Marvin, Vogon, Zaphod). Unlike the chat engines, these do not run an
 * LLM tool loop — they drive workers synchronously in bounded loops and
 * go BLOCKED between steps. Without a check between drives an ESC /
 * {@code /pause} (which halts+pauses every process in the session, the
 * orchestrator included) would only take effect after the current turn's
 * loops finished, because the queued PAUSED task can't run on the busy
 * lane until then.
 *
 * <p>{@link #check(ThinkProcessService, String)} throws
 * {@link OrchestratorInterruptedException} at a safe boundary so a loop
 * bails promptly. Engines catch it at the turn boundary and either park
 * PAUSED (halt flag) or leave the status the pause handler already set.
 *
 * <p>The orchestrators persist their progress (strategy state / task
 * tree), so bailing mid-turn is safe: on resume the turn re-runs from the
 * persisted state. See {@code planning/orchestrator-esc.md}.
 */
public final class OrchestratorInterrupt {

    /** What tripped the interrupt — drives the post-turn status. */
    public enum Kind {
        /** No interrupt. */
        NONE,
        /** Pause handler already flipped the status (SUSPENDED/PAUSED/CLOSED) — leave it. */
        STATUS,
        /** Out-of-band halt flag — the engine must clear it and park PAUSED. */
        HALT
    }

    /**
     * Probes the process for a mid-orchestration interrupt. HALT takes
     * priority so the flag is cleared even when the status has not (yet)
     * flipped.
     */
    public static Kind probe(ThinkProcessService svc, String processId) {
        if (svc.isHaltRequested(processId)) {
            return Kind.HALT;
        }
        ThinkProcessStatus s = svc.findById(processId)
                .map(ThinkProcessDocument::getStatus)
                .orElse(null);
        if (s == ThinkProcessStatus.SUSPENDED
                || s == ThinkProcessStatus.PAUSED
                || s == ThinkProcessStatus.CLOSED) {
            return Kind.STATUS;
        }
        return Kind.NONE;
    }

    /**
     * Throws {@link OrchestratorInterruptedException} when the process is
     * interrupted; no-op otherwise. Call at loop tops and before each
     * synchronous worker drive.
     */
    public static void check(ThinkProcessService svc, String processId) {
        Kind k = probe(svc, processId);
        if (k != Kind.NONE) {
            throw new OrchestratorInterruptedException(k);
        }
    }

    private OrchestratorInterrupt() {}
}
