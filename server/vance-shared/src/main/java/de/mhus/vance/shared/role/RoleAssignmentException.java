package de.mhus.vance.shared.role;

/**
 * A role change that breaks an assignment rule, or names something that does
 * not exist. Distinct from
 * {@link de.mhus.vance.shared.permission.PermissionDeniedException}: the actor
 * may administer roles here — this particular change is what is refused.
 */
public class RoleAssignmentException extends RuntimeException {

    /** Why the change was refused. */
    public enum Kind {
        /** The user, team or project named does not exist. */
        NOT_FOUND,
        /** The change breaks a rule (above own role, last tenant admin, …). */
        REFUSED
    }

    private final Kind kind;

    public RoleAssignmentException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
