package de.mhus.vance.addon.brain.designer;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Designer Brain addon. Discovered by Spring Boot
 * via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * and component-scans this package, so the {@link DesignerApplication}
 * service, {@link DesignerContentController} REST controller,
 * {@link DesignerPreviewTokenService} and the {@code designer_app_create}
 * server tool register themselves into the Brain context.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = DesignerAddon.class)
public class DesignerAddon {}
