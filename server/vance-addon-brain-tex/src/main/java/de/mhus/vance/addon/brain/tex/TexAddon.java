package de.mhus.vance.addon.brain.tex;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Brain-addon entrypoint. Spring Boot picks this up via
 * {@code META-INF/spring/AutoConfiguration.imports}; the ComponentScan
 * then registers the compile executors ({@link Tex2PdfExecutor}),
 * {@link TexService} (executor resolution), and the Damogran
 * {@code tex-task} ({@link TexDamogranTask}) — LaTeX compilation runs as a
 * Damogran compose task, not a standalone tool/kind.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = TexAddon.class)
public class TexAddon {}
