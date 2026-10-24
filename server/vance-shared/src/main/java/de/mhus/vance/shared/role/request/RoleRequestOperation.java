package de.mhus.vance.shared.role.request;

/**
 * What a {@link RoleRequestDocument} would do if approved.
 *
 * <p>Both directions are approval-gated. Revoking looks like the safe
 * direction — it only ever takes rights away — but an unwanted revoke is a
 * clean denial of service: it can remove the last admin of a scope, after
 * which nobody can approve anything, not even undoing it. A silent revoke is
 * also harder to notice than a silent grant, because something is missing
 * rather than present.
 */
public enum RoleRequestOperation {
    GRANT,
    REVOKE
}
