package de.mhus.vance.shared.audit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.audit.*} — runtime configuration for {@link AuditService}.
 *
 * <p>Defaults: ASYNC dispatch via a single worker thread, 10k bounded
 * queue, 5s shutdown drain budget, no consumers active (the
 * {@code LogAuditConsumer} is opt-in via {@code vance.audit.consumers.log.enabled}).
 */
@Data
@ConfigurationProperties(prefix = "vance.audit")
public class AuditServiceProperties {

    /**
     * Delivery mode applied after {@code @PostConstruct}. Before that,
     * the service is always SYNC so events emitted during bean wiring
     * are not lost.
     */
    private AuditMode mode = AuditMode.ASYNC;

    /**
     * Bounded capacity of the in-memory queue used in ASYNC mode.
     * When full, {@link AuditService#record(AuditEventDto)} drops the
     * event and increments {@code vance.audit.dropped}.
     */
    private int queueSize = 10_000;

    /**
     * Maximum time {@code @PreDestroy} waits for the worker thread to
     * terminate after the queue has been drained.
     */
    private long drainTimeoutMs = 5_000;

    private Consumers consumers = new Consumers();

    @Data
    public static class Consumers {
        private Log log = new Log();
    }

    @Data
    public static class Log {
        /**
         * Activates {@link LogAuditConsumer}. When {@code true}, the
         * consumer is registered as a Spring bean and picked up by
         * {@link AuditService} at construction time.
         */
        private boolean enabled = false;
    }
}
