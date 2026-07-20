package de.mhus.vance.addon.brain.desktop;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Common Desktop Brain addon. Discovered by Spring
 * Boot via {@code META-INF/spring/.../AutoConfiguration.imports} and
 * component-scans this package so the {@link DesktopApplication}
 * service, {@link DesktopController} REST controller and the
 * {@code desktop_app_create} server tool register themselves into the
 * Brain context.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = CommonDesktopAddon.class)
public class CommonDesktopAddon {
}
