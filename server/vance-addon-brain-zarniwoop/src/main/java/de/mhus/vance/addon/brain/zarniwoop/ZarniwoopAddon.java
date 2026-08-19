package de.mhus.vance.addon.brain.zarniwoop;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Search addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans this
 * package so {@link SearchApplication} and the REST surface register themselves.
 *
 * <p>Consumes the brain's Zarniwoop services and nothing else addon-shaped.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.zarniwoop",
})
public class ZarniwoopAddon {
}
