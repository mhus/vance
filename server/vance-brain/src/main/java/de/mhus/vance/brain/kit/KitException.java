package de.mhus.vance.brain.kit;

import org.jspecify.annotations.Nullable;

/**
 * Thrown by the kit subsystem for problems that prevent an operation
 * from completing — clone failure, malformed {@code kit.yaml}, inherit
 * cycle, missing manifest on update, etc. Soft warnings (skipped
 * password, no-op cleanup) flow through {@code warnings} on the
 * {@code KitOperationResultDto} instead.
 */
public class KitException extends RuntimeException {

    public KitException(String message) {
        super(message);
    }

    public KitException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
