package de.mhus.vance.brain.hactar;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Spring's {@link ApplicationEventPublisher} so all
 * task-completion sources funnel through one type and tests can swap
 * the bus out cleanly (plan §6.4).
 *
 * <p>One subscriber consumes the events: {@code HactarWorkflowService.onTaskCompleted}.
 * Spring's event delivery is synchronous in-process — completion handling
 * runs on the publisher's thread unless we explicitly switch executors,
 * which we deliberately don't (the project lane already serialises).
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
public class HactarCompletionEventBus {

    private final ApplicationEventPublisher publisher;

    public void publish(TaskCompletedEvent event) {
        publisher.publishEvent(event);
    }
}
