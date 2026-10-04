package de.mhus.vance.addon.brain.nutrimat;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Nutrimat Brain addon — the experimental loop
 * laboratory. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans the
 * {@code de.mhus.vance.addon.brain.nutrimat} package so the {@code nutrimat-*}
 * engine beans register themselves into the think-engine registry and the
 * judge service is available to the loop natures.
 *
 * <p>Deliberately self-contained: the loop implementations are adapted, not
 * linked, against the productive engine strand (Ford / StructuredActionEngine)
 * — see {@code planning/nutrimat-engine.md}. What is shared is infrastructure
 * only (AiChat, LightLlm, chat log, memory compaction, guards).
 */
@AutoConfiguration
@ComponentScan(
        basePackages = {
            "de.mhus.vance.addon.brain.nutrimat",
        })
public class NutrimatAddon {}
