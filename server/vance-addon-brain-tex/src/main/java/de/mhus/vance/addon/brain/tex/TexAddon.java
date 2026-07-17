package de.mhus.vance.addon.brain.tex;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Brain-addon entrypoint. Spring Boot picks this up via
 * {@code META-INF/spring/AutoConfiguration.imports}; the ComponentScan
 * then registers the {@code tex-compose} kind handler, the
 * {@link TexService}, the {@code tex2pdf} tool + REST controller, and
 * the compile executors from this package into the Brain context.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = TexAddon.class)
public class TexAddon {}
