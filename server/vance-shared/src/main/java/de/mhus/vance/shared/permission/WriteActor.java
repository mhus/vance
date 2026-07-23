package de.mhus.vance.shared.permission;

/**
 * Who is performing a write, and why — the mandatory actor threaded into
 * every {@code DocumentService} write so authorization happens at the
 * source with no bypass. Bundles the real acting {@link SecurityContext}
 * (never lost — audit stays correct) with the {@link WriteReason}.
 *
 * <p>Use the factories:
 * <ul>
 *   <li>{@link #user(SecurityContext)} — a user-initiated write (normal
 *       role check).</li>
 *   <li>{@link #system(SecurityContext)} — a trusted server write of a
 *       system resource on behalf of that user (resolver allows it, the
 *       user is still recorded).</li>
 *   <li>{@link #SYSTEM} — a purely internal write with no user context
 *       (migration, bootstrap, lifecycle).</li>
 * </ul>
 *
 * <p>Only server code can build a {@code SYSTEM}-reason actor; a
 * user-driven surface always uses {@link #user(SecurityContext)}, so the
 * trust signal cannot be forged from user input.
 */
public record WriteActor(SecurityContext subject, WriteReason reason) {

    /** A purely internal write with no user (migration, bootstrap, lifecycle). */
    public static final WriteActor SYSTEM = new WriteActor(SecurityContext.SYSTEM, WriteReason.SYSTEM);

    /** User-initiated write — the resolver applies the normal role check. */
    public static WriteActor user(SecurityContext subject) {
        return new WriteActor(subject, WriteReason.USER);
    }

    /**
     * Trusted server write of a system resource on behalf of {@code subject}
     * — allowed regardless of the user's role, the real user kept for audit.
     */
    public static WriteActor system(SecurityContext subject) {
        return new WriteActor(subject, WriteReason.SYSTEM);
    }
}
