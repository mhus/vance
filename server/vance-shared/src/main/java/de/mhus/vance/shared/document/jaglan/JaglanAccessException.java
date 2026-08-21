package de.mhus.vance.shared.document.jaglan;

/**
 * The mount refused the operation — read-only source, read-only subtree, or
 * an operation the protocol does not implement.
 *
 * <p>Separate from {@link JaglanUnavailableException} because the two need
 * different answers: a refusal is a stable property of the source and the
 * caller should stop asking (HTTP 409, a named tool message), while
 * unavailability is transient and worth retrying.
 *
 * <p>Never a silent no-op. A write that appears to succeed and is gone after
 * a reload is worse than a rejection, and the tree already has the pattern —
 * {@code DocumentLockedException} carries the same kind of refusal through
 * REST, the tool surface and kit-apply.
 */
public class JaglanAccessException extends RuntimeException {

    private final String mount;

    public JaglanAccessException(String mount, String message) {
        super(message);
        this.mount = mount;
    }

    /** The mount that refused, for the message and for logs. */
    public String getMount() {
        return mount;
    }
}
