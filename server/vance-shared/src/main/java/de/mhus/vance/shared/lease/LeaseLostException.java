package de.mhus.vance.shared.lease;

/**
 * The holder no longer owns the lease — the holder document is gone
 * (idle-reaped past the TTL, or pod-reaped as dead). Cooperative
 * semantics: the underlying resource is not destroyed by this, but the
 * holder has lost its claim and must stop using the resource. Re-acquiring
 * is a consumer decision at the call site, never automatic (v1).
 */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(String message) {
        super(message);
    }
}
