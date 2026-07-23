package de.mhus.vance.addon.brain.journal;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Journal Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans
 * only the journal packages so the journal services / tools register
 * themselves into the Brain context.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.journal",
})
public class JournalAddon {
}
