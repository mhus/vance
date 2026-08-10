package de.mhus.vance.simpleauth;

/**
 * What a {@link PermissionRequestDocument} would do if approved.
 *
 * <p>Both directions are approval-gated. Revoking looks like the safe
 * direction — it only ever takes rights away — but an unwanted revoke is
 * a clean denial of service: in the worst case it removes the last ADMIN
 * of a scope, after which nobody can approve anything, not even undoing
 * it. A silent revoke is also harder to notice than a silent grant,
 * because something is missing rather than present.
 */
public enum PermissionRequestOperation {
    GRANT,
    REVOKE
}
