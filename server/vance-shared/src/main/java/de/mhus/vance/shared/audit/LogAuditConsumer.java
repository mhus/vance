package de.mhus.vance.shared.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Audit consumer that writes every event to SLF4J at INFO. Opt-in via
 * {@code vance.audit.consumers.log.enabled=true} — disabled by default
 * so production logs aren't flooded by environments that haven't tuned
 * their action vocabulary yet.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "vance.audit.consumers.log", name = "enabled", havingValue = "true")
public class LogAuditConsumer implements AuditConsumer {

    @Override
    public void consume(AuditEventDto event) {
        log.info(
                "AUDIT ts={} action={} severity={} outcome={} actor={} tenant={} project={} session={} target={} message={} details={}",
                event.getTimestamp(),
                event.getAction(),
                event.getSeverity(),
                event.getOutcome(),
                event.getActor(),
                event.getTenantId(),
                event.getProjectId(),
                event.getSessionId(),
                event.getTarget(),
                event.getMessage(),
                event.getDetails());
    }
}
