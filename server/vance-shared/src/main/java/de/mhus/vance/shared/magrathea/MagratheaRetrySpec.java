package de.mhus.vance.shared.magrathea;

import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import java.util.Set;

/**
 * Parsed {@code retry:} block on a state. When a terminal outcome
 * matches one of {@link #onErrorKinds}, the {@code MagratheaTaskExecutor}
 * re-enqueues a fresh task with {@link #backoffSeconds} delay until
 * {@link #maxAttempts} is hit; beyond that the {@code catch:} block
 * takes over (plan §5).
 *
 * @param maxAttempts Total attempts including the first. Default 1.
 * @param onErrorKinds Error categories that qualify for retry.
 * @param backoffSeconds Constant delay between attempts (no exponential
 *                       backoff in v1).
 */
public record MagratheaRetrySpec(
        int maxAttempts,
        Set<MagratheaErrorKind> onErrorKinds,
        int backoffSeconds) {

    public static MagratheaRetrySpec none() {
        return new MagratheaRetrySpec(1, Set.of(), 30);
    }
}
