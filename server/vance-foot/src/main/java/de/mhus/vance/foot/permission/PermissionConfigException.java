package de.mhus.vance.foot.permission;

/**
 * Thrown when {@code ~/.vance/permissions.yaml} cannot be parsed or a
 * rule fails to compile (malformed command regex, unreadable file).
 * The caller decides the fallback — {@code PermissionService} logs it
 * and falls back to a floor-only policy so a broken file never silently
 * disables the sandbox.
 */
public class PermissionConfigException extends RuntimeException {

    public PermissionConfigException(String message) {
        super(message);
    }

    public PermissionConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
