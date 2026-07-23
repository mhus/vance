package de.mhus.vance.addon.brain.gtd;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the GTD Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans only
 * the gtd packages.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.gtd",
})
public class GtdAddon {
}
