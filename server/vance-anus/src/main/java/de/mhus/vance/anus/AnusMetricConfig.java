package de.mhus.vance.anus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Anus does not pull in {@code spring-boot-starter-actuator}, so the
 * Boot-managed {@link MeterRegistry} bean is absent. Several services
 * in {@code vance-shared} (e.g. {@code MetricService}) require one
 * unconditionally — provide a {@link SimpleMeterRegistry} so the
 * component scan can wire them without dragging actuator endpoints
 * into the admin shell.
 *
 * <p>The metrics are never exported anywhere in anus — they live in-
 * memory for the duration of the REPL session. That's intentional:
 * anus is interactive, not a long-running pod.
 */
@Configuration
public class AnusMetricConfig {

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
