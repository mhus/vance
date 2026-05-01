package de.mhus.vance.foot.transfer;

/**
 * Thrown when a transfer path can't be resolved to a sandboxed
 * absolute path — either because the input is malformed, escapes the
 * sandbox, or the underlying filesystem refuses the operation.
 */
public class TransferPathException extends RuntimeException {

    public TransferPathException(String message) {
        super(message);
    }

    public TransferPathException(String message, Throwable cause) {
        super(message, cause);
    }
}
