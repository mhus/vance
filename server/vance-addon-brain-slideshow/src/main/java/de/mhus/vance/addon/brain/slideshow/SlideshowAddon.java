package de.mhus.vance.addon.brain.slideshow;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Slideshow Brain addon. Discovered by Spring Boot
 * via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * and component-scans this package, so the {@link SlideshowApplication}
 * service, {@link SlideshowController} REST controller and the
 * {@code slideshow_*} server tools register themselves into the Brain
 * context.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = SlideshowAddon.class)
public class SlideshowAddon {
}
