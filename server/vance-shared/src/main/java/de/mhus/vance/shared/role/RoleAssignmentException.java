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
        /**
         * The change reaches above the actor's own tenant role — assigning a
         * stronger role, or changing someone who holds one. Someone higher up
         * could make it, so a caller may turn this into a role request.
         */
        BEYOND_AUTHORITY,
        /** The change breaks a rule (last tenant admin, persons only, external roles, …). */
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
