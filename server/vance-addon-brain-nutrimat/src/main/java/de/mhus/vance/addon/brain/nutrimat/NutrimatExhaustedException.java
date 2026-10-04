package de.mhus.vance.addon.brain.nutrimat;

/**
 * Raised by a Nutrimat loop nature when its work budget is exhausted and the
 * nature's policy treats exhaustion as a hard failure (the {@code redbull}
 * family) instead of a recoverable stop.
 *
 * <p>Historical note: the "exhausted state" of the old Ford implementation was
 * an exception, not a persisted state — this class reactivates that shape. The
 * turn shell in {@link AbstractNutrimat} catches it and maps it onto the
 * existing terminal vocabulary (a visible error reply; a worker closes
 * {@code INCOMPLETE}). No new {@code ThinkProcessStatus} value is introduced;
 * the exception lives in the turn, not in the database.
 */
public class NutrimatExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NutrimatExhaustedException(String message) {
        super(message);
    }
}
