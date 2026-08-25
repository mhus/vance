package de.mhus.vance.shared.user;

/**
 * Notified when an account comes into existence or goes away — the seam
 * everything that keys on a <em>user name</em> hangs off.
 *
 * <p>It exists because a name is not an identity: grants, requests and
 * per-user stores key on {@code (tenant, name)}, not on the Mongo id, so
 * they survive the document unless somebody removes them — and a name comes
 * back (service accounts follow a scheme like {@code _trillian-void-a7f3},
 * human logins get reused). Whoever owns such a store has to be told; before
 * this interface {@link UserService} called one hard-wired SPI
 * ({@code PermissionBootstrap}) and everybody else found out never.
 *
 * <p><b>Both ends, on purpose.</b> Deletion is the obvious half. Creation is
 * the second line of defence for exactly the same hazard: a leftover grant
 * that nobody cleaned up is inherited, silently, by the next account minted
 * under that name. A listener that clears state on create closes that hole
 * without anyone having to prove the delete path always ran.
 *
 * <p><b>Optional and plural</b> — resolved through {@code ObjectProvider}, so
 * no implementation is the normal state (a Brain without the simple-auth
 * addon, or an enterprise governor that manages rights externally).
 *
 * <p><b>The two halves fail differently, and that asymmetry is the contract:</b>
 * {@link #onUserDeleted} runs <em>before</em> the document is removed and a
 * throw <em>aborts the delete</em> — a grant without its subject is worse than
 * a user who could not be deleted. {@link #onUserCreated} runs after the
 * insert, where aborting is no longer possible, so a throw is logged and the
 * remaining listeners still run.
 */
public interface UserLifecycleListener {

    /**
     * A new account exists. Fired after the document was written, once per
     * account — {@code ensureVanceServiceAccount} does not fire on the calls
     * that find the account already there.
     *
     * <p>Best-effort: an exception is logged, the create stands.
     */
    default void onUserCreated(UserDocument user) {
        // nothing to do by default
    }

    /**
     * An account is about to be removed. Fired before the document is
     * deleted, so an implementation that cannot clean up can stop the
     * deletion by throwing.
     *
     * <p>Must be idempotent: callers that clean up orphans invoke the same
     * work directly, and it may therefore run twice for one account.
     */
    default void onUserDeleted(String tenantId, String name) {
        // nothing to do by default
    }
}
