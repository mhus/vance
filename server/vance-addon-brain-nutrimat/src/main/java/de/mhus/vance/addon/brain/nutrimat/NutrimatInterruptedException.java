package de.mhus.vance.addon.brain.nutrimat;

/**
 * Thrown by {@link AbstractNutrimat#round} when the process was interrupted
 * (ESC / {@code /pause}, a status flip to SUSPENDED/PAUSED/CLOSED) before the
 * next model call. It carries the interrupt out of whatever loop a nature
 * built — the turn shell catches it and parks the process without surfacing
 * an answer. A nature never needs its own interrupt check, and must not
 * swallow this exception: rethrow it from any broad {@code catch}.
 */
public class NutrimatInterruptedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean forcePause;

    public NutrimatInterruptedException(boolean forcePause) {
        super(forcePause ? "halt requested" : "process status interrupt");
        this.forcePause = forcePause;
    }

    /** Halt-flag interrupt → park PAUSED; status flip → leave the status as-is. */
    public boolean forcePause() {
        return forcePause;
    }
}
