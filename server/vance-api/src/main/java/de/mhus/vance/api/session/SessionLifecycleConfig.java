package de.mhus.vance.api.session;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Five typed properties governing a session's lifecycle behaviour.
 * Resolved at {@code session-create} from the bootstrap-recipe's
 * {@code profiles.{profile}.session} block (with settings + caller
 * overrides on top), persisted on the {@code SessionDocument}, then
 * <strong>immutable</strong> for the lifetime of the session.
 *
 * <p>Lifecycle code reads only this config; it does <em>not</em>
 * branch on the session's profile string.
 *
 * <p>See {@code specification/session-lifecycle.md} §5.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("session")
public class SessionLifecycleConfig {

    /** What happens on client-disconnect. */
    @Builder.Default
    private DisconnectPolicy onDisconnect = DisconnectPolicy.KEEP_OPEN;

    /** What happens when all engines are idle for {@link #idleTimeoutMs}. */
    @Builder.Default
    private IdlePolicy onIdle = IdlePolicy.NONE;

    /**
     * What happens after a non-{@code FORCED} suspend. {@code FORCED}
     * always uses the system-wide {@code FORCED_FLOOR}.
     */
    @Builder.Default
    private SuspendPolicy onSuspend = SuspendPolicy.KEEP;

    /**
     * Idle threshold in milliseconds. Ignored when {@code onIdle=NONE}.
     */
    @Builder.Default
    private long idleTimeoutMs = 1_800_000L; // 30 min

    /**
     * Time spent in {@code SUSPENDED} (for non-FORCED causes) before
     * the sweeper closes the session.
     */
    @Builder.Default
    private long suspendKeepDurationMs = 86_400_000L; // 24 h

    /** Hard-coded safe defaults — used when no recipe and no settings supply values. */
    public static SessionLifecycleConfig safeDefault() {
        return SessionLifecycleConfig.builder().build();
    }
}
