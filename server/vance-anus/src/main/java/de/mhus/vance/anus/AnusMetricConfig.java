package de.mhus.vance.anus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Anus does not pull in {@code spring-boot-starter-actuator}, so the
 * Boot-managed {@link MeterRegistry} bean is absent. Several services
 * in {@code vance-shared} (e.g. {@code MetricService}) require one
 * unconditionally.
 *
 * <p>The registry is a {@link CompositeMeterRegistry} that keeps an in-memory
 * {@link SimpleMeterRegistry} as its first child, so the interactive shell
 * behaves as before. An addon that runs Anus as a long-lived service (the cloud
 * controller) attaches an exporting registry to the composite; nothing is exported
 * unless one is attached.
 */
@Configuration
public class AnusMetricConfig {

    @Bean
    public CompositeMeterRegistry meterRegistry() {
        CompositeMeterRegistry composite = new CompositeMeterRegistry();
        composite.add(new SimpleMeterRegistry());
        return composite;
    }
}
