package de.mhus.vance.addon.brain.canvas;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Canvas Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans
 * the {@code de.mhus.vance.addon.brain.canvas} package so the canvas
 * service and {@code canvas_*} tools register themselves into the Brain
 * context.
 *
 * <p>Self-contained: no compile- or runtime-dependency on the workbook
 * addon. Reuses only Vance-wide facilities ({@code DocumentService},
 * the {@code $meta}-header machinery, the App-Foundation) — see
 * {@code planning/canvas.md} §7.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.canvas",
})
public class CanvasAddon {
}
