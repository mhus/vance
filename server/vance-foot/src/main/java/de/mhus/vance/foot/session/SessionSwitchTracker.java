package de.mhus.vance.foot.session;

import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Single-level back-stack for the foot's session-switch flow — mirror
 * of {@code ChatApp.previousSessionId} in vance-face. When a server-
 * pushed {@code switch-to} fires (typically Eddie's {@code MEDIATE}
 * action), the current session id is pushed here so the {@code /hub}
 * slash command can later resume it.
 *
 * <p>v1 is single-level. v2 may grow this into a real stack for
 * multi-hop project context switching ({@code switch-to A} →
 * {@code switch-to B} → {@code /hub} returns to A, second
 * {@code /hub} returns to the original hub).
 *
 * <p>Process-local only — there is no persistence. A foot restart
 * loses the back-stack along with the rest of the live state; the
 * server has no concept of "which session was previous" either.
 *
 * <p>Spec: {@code specification/eddie-engine.md} §8.5.4.
 */
@Service
public class SessionSwitchTracker {

    private final AtomicReference<@Nullable String> previousSessionId = new AtomicReference<>();

    /** {@code null} when we're at the hub already. */
    public @Nullable String previousSessionId() {
        return previousSessionId.get();
    }

    /** Push the current session id (the one we're leaving) onto the back-stack. */
    public void push(@Nullable String sessionId) {
        previousSessionId.set(sessionId);
    }

    /** Pop and return — used by {@code /hub} to discover the return target. */
    public @Nullable String pop() {
        return previousSessionId.getAndSet(null);
    }

    public void clear() {
        previousSessionId.set(null);
    }
}
