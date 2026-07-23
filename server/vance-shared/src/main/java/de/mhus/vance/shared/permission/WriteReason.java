package de.mhus.vance.shared.permission;

/**
 * Why a write is happening — passed alongside the acting
 * {@link SecurityContext} into the authorization check so the resolver can
 * treat trusted internal operations differently from user-initiated ones,
 * <em>without</em> losing the real actor (audit stays correct).
 *
 * <p>Only Java code sets this; a user-driven surface (LLM tool, REST,
 * WebSocket) never passes {@code SYSTEM}, so it cannot be forged from
 * user input.
 *
 * <p>Deliberately minimal. Further reasons (e.g. {@code MIGRATION},
 * {@code KIT}, {@code IMPORT}) are added only when a concrete policy or
 * audit need arises — the enum makes that possible without re-threading the
 * write path.
 */
public enum WriteReason {

    /** A user-initiated write — the resolver applies the normal role check. Default. */
    USER,

    /**
     * A trusted internal write of a system-owned resource (log, recipe,
     * config, …) done by server code on behalf of a user. The resolver
     * allows it regardless of the user's role; the {@link SecurityContext}
     * still carries the real user for audit.
     */
    SYSTEM
}
