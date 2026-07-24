package de.mhus.vance.addon.brain.issues;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Issues Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans only
 * the issues packages.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.issues",
})
public class IssuesAddon {
}
