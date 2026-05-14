package de.mhus.vance.shared.hactar;

import de.mhus.vance.api.hactar.HactarErrorKind;
import java.util.Set;

/**
 * Parsed {@code retry:} block on a state. When a terminal outcome
 * matches one of {@link #onErrorKinds}, the {@code HactarTaskExecutor}
 * re-enqueues a fresh task with {@link #backoffSeconds} delay until
 * {@link #maxAttempts} is hit; beyond that the {@code catch:} block
 * takes over (plan §5).
 *
 * @param maxAttempts Total attempts including the first. Default 1.
 * @param onErrorKinds Error categories that qualify for retry.
 * @param backoffSeconds Constant delay between attempts (no exponential
 *                       backoff in v1).
 */
public record HactarRetrySpec(
        int maxAttempts,
        Set<HactarErrorKind> onErrorKinds,
        int backoffSeconds) {

    public static HactarRetrySpec none() {
        return new HactarRetrySpec(1, Set.of(), 30);
    }
}
