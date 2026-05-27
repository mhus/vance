package de.mhus.vance.shared.audit;

/**
 * Sink for {@link AuditEventDto}s. Implementations must be thread-safe —
 * they may be invoked concurrently from the caller thread (SYNC mode)
 * and from the audit worker (ASYNC mode), in particular during a runtime
 * mode switch when the worker is still draining and SYNC callers already
 * dispatch directly.
 *
 * <p>Consumers must not throw checked exceptions. Runtime exceptions are
 * caught by {@link AuditService} so a misbehaving consumer cannot kill
 * the pipeline, but they will be counted and logged.
 */
@FunctionalInterface
public interface AuditConsumer {

    void consume(AuditEventDto event);
}
