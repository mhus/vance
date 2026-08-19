package de.mhus.vance.addon.brain.centauri;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Feeds addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans this
 * package so {@link FeedsApplication} and the REST surface register themselves.
 *
 * <p>Self-contained apart from the brain's own Centauri stack — it consumes
 * {@code CentauriService} and nothing else addon-shaped.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.centauri",
})
public class CentauriAddon {
}
