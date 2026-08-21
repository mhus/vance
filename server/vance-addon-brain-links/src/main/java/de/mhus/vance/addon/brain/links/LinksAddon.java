package de.mhus.vance.addon.brain.links;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the links Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans
 * the {@code de.mhus.vance.addon.brain.links} package so
 * {@link LinksApplication} and the {@code links_*} tools register
 * themselves into the Brain context.
 *
 * <p>Self-contained: it reuses the brain's link-preview proxy and the
 * app foundation, and depends on no other addon.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.links",
})
public class LinksAddon {
}
