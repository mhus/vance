package de.mhus.vance.addon.brain.scribble;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Scribble Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans
 * the {@code de.mhus.vance.addon.brain.scribble} package so the scribble
 * service, kind handler and REST controller register themselves into the
 * Brain context.
 *
 * <p>Self-contained: no compile- or runtime-dependency on any other
 * addon. Reuses only Vance-wide facilities ({@code DocumentService},
 * the {@code $meta}-header machinery, the kind-handler registry) — see
 * {@code planning/scribble.md} §6.
 */
@AutoConfiguration
@ComponentScan(
        basePackages = {
            "de.mhus.vance.addon.brain.scribble",
        })
public class ScribbleAddon {}
