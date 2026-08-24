package de.mhus.vance.addon.brain.bistromath;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Bistromath Brain addon — the application runtime.
 * Discovered via {@code META-INF/spring/.../AutoConfiguration.imports};
 * component-scans this package so {@link BistromathApplication}, the REST
 * controller and the {@code bistromath_*} tools register themselves.
 *
 * <p>Self-contained: it reads and writes documents through
 * {@code DocumentService} and depends on no other addon.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = BistromathAddon.class)
public class BistromathAddon {
}
