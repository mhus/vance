package de.mhus.vance.api.session;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What happens when the bound client disconnects from a session
 * (clean unbind or lost connection). Set immutably at session-create
 * from the resolved bootstrap-recipe's {@code profiles.{profile}.session}
 * block.
 *
 * <p>See {@code specification/session-lifecycle.md} §5 + §8.
 *
 * <ul>
 *   <li>{@link #SUSPEND} — session → {@code SUSPENDED}, all engines
 *       cascade to {@code SUSPENDED}. Use when the disconnect makes
 *       the session truly unable to make progress (no caller likely
 *       to come back, no tools left worth running for).</li>
 *   <li>{@link #KEEP_OPEN} — session stays in its current status,
 *       engines run on. Default for user-facing profiles
 *       ({@code foot}/{@code web}/{@code mobile}) — work may continue
 *       (research, LLM-only turns); idle-detection (§7) parks the
 *       session once it actually goes quiet.</li>
 *   <li>{@link #CLOSE} — session → {@code CLOSED} immediately. Typical
 *       for {@code daemon}-profile (RPC-light, reconnect = new session).</li>
 * </ul>
 */
@GenerateTypeScript("session")
public enum DisconnectPolicy {
    SUSPEND,
    KEEP_OPEN,
    CLOSE
}
