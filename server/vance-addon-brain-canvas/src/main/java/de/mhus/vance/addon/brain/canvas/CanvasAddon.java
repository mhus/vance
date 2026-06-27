package de.mhus.vance.addon.brain.canvas;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Canvas Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans
 * this package so {@link CanvasService} and the {@code canvas_*} server
 * tools register themselves into the Brain context.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = CanvasAddon.class)
public class CanvasAddon {
}
