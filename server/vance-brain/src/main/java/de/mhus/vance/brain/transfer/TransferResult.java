package de.mhus.vance.brain.transfer;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of a brain-initiated transfer, returned to the LLM as the
 * tool result.
 */
public record TransferResult(
        boolean ok,
        long bytesWritten,
        @Nullable String hash,
        @Nullable String error) {

    public static TransferResult ok(long bytesWritten, String hash) {
        return new TransferResult(true, bytesWritten, hash, null);
    }

    public static TransferResult fail(String error) {
        return new TransferResult(false, 0, null, error);
    }
}
