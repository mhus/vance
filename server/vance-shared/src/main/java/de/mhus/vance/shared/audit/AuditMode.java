package de.mhus.vance.shared.audit;

/**
 * Delivery mode of the {@link AuditService}. Switchable at runtime via
 * {@link AuditService#setMode(AuditMode)}.
 */
public enum AuditMode {
    /** Caller thread dispatches the event to all consumers inline. */
    SYNC,
    /** Event is queued; a single worker thread dispatches it asynchronously. */
    ASYNC
}
