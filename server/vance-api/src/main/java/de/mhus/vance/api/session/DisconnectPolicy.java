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
 *       cascade to {@code SUSPENDED}. Typical for {@code foot}-profile.</li>
 *   <li>{@link #KEEP_OPEN} — session stays {@code OPEN}, engines run on.
 *       Typical for {@code web}/{@code mobile}; idle-detection later.</li>
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
