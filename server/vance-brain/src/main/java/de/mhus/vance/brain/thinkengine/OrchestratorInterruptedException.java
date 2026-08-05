package de.mhus.vance.brain.thinkengine;

/**
 * Thrown by {@link OrchestratorInterrupt#check} to unwind a
 * worker-driving engine's bounded orchestration loops promptly on a
 * mid-turn ESC / {@code /pause}. It is a control-flow signal, NOT a
 * failure: engines catch it at the turn boundary and park the process
 * (PAUSED for a halt flag, or leave the pause-handler status), rather
 * than marking the phase / node failed.
 *
 * <p>Because these engines wrap synchronous worker drives in broad
 * {@code catch (RuntimeException)} blocks that mean "worker turn failed",
 * every such block on the drive path must re-throw this exception (a
 * preceding {@code catch (OrchestratorInterruptedException) { throw; }})
 * so the interrupt reaches the turn-boundary handler instead of being
 * mistaken for a phase failure.
 */
public final class OrchestratorInterruptedException extends RuntimeException {

    private final OrchestratorInterrupt.Kind kind;

    public OrchestratorInterruptedException(OrchestratorInterrupt.Kind kind) {
        super("orchestration interrupted: " + kind);
        this.kind = kind;
    }

    public OrchestratorInterrupt.Kind kind() {
        return kind;
    }
}
