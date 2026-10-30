package de.mhus.vance.brain.resources;

import de.mhus.vance.shared.resourceoverride.ResourceOverrideRef;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the valid version of every centrally managed section (default every
 * 60 s) and reloads the section's {@link ResourceOverrideConsumer} when it is
 * not the version the consumer last loaded. One small query per section and
 * poll — the files are only read when the version changed. A failed poll
 * changes nothing; the next one tries again.
 *
 * <p>Only present with {@code vance.resource-override.central.enabled=true}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vance.resource-override.central", name = "enabled", havingValue = "true")
public class ResourceOverrideWatcher {

    private final ResourceOverrideService overrides;
    private final BundledResourceResolver resolver;
    private final List<ResourceOverrideConsumer> consumers;

    @Scheduled(
            fixedDelayString = "${vance.resource-override.central.poll-interval:PT60S}",
            initialDelayString = "${vance.resource-override.central.poll-interval:PT60S}")
    public void poll() {
        for (ResourceOverrideConsumer consumer : consumers) {
            String section = consumer.section();
            if (!resolver.isCentralManaged(section)) {
                continue;
            }
            try {
                String valid = overrides
                        .effective(section)
                        .map(ResourceOverrideRef::key)
                        .orElse(BundledResourceResolver.NONE);
                if (!valid.equals(resolver.appliedKey(section))) {
                    log.info("Resource override '{}': version {} is valid, reloading", section, valid);
                    consumer.reload();
                }
            } catch (RuntimeException e) {
                log.warn("Resource override '{}': poll failed, keeping the current state: {}", section, e.toString());
            }
        }
    }
}
