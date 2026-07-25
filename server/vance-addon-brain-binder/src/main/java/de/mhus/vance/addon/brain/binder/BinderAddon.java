package de.mhus.vance.addon.brain.binder;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Binder Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans
 * the {@code de.mhus.vance.addon.brain.binder} package so the
 * {@link BinderApplication} and the {@code binder_*} tools register
 * themselves into the Brain context.
 *
 * <p>Self-contained: no compile- or runtime-dependency on the workbook
 * or canvas addons. Reuses only Vance-wide facilities
 * ({@code DocumentService}, the App-Foundation, the {@code $meta}-header
 * machinery) — see {@code planning/app-binder.md}.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.binder",
})
public class BinderAddon {
}
