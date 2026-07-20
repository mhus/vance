package de.mhus.vance.addon.brain.wiki;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Wiki Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans
 * <b>only</b> the wiki package so the wiki services / tools register
 * themselves into the Brain context.
 *
 * <p>The {@code de.mhus.vance.addon.brain.workpage} package (the
 * {@code kind: workpage} document type + its tools) belongs to the
 * workbook addon and is deliberately <b>not</b> scanned here — the wiki
 * reuses the {@code workpage} kind as a data format, not the code. Wiki
 * pages are written as plain Markdown with a {@code $meta.kind: workpage}
 * header via {@code DocumentService}.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.wiki",
})
public class WikiAddon {
}
