package de.mhus.vance.api.toolhealth;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An error that carries the upstream service's own retry hint
 * ({@code Retry-After}).
 *
 * <p>Exists for one reader: {@code AgrajagChecker}'s
 * {@code COOLDOWN_FROM_RETRY_AFTER} cooldown resolution. Until now that
 * branch fell back to the intermittent default because a thrown error had
 * no header surface at all — the cooldown pattern config already said
 * {@code cooldown: header:retry-after} while nothing could ever supply the
 * header. A transport error that parsed {@code Retry-After} implements this
 * interface and the checker prefers the hint over its own defaults.
 *
 * <p>Deliberately not an exception type: an error may already be some other
 * exception (a transport failure, a protocol failure) and still know when
 * the upstream service wants to be asked again.
 */
public interface RetryAfterError {

    /**
     * When the upstream service said to ask again, or {@code null} when it
     * said nothing. Callers clamp past timestamps — a hint in the past
     * carries no instruction.
     */
    @Nullable
    Instant retryAfter();
}
