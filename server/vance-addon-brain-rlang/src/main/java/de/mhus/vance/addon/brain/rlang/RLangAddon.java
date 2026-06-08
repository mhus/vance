package de.mhus.vance.addon.brain.rlang;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Brain-addon entrypoint. Spring Boot picks this up via
 * META-INF/spring/AutoConfiguration.imports; ComponentScan then
 * registers the tool, daemon manager, health probe and the
 * @ConfigurationProperties bean from this package.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = RLangAddon.class)
public class RLangAddon {}
