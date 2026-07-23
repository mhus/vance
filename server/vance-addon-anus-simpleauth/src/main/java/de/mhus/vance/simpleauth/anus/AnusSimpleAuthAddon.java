package de.mhus.vance.simpleauth.anus;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the anus-side Simple-Auth addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans the
 * package so the {@code permission grant *} spring-shell commands register in
 * the anus context. Requires the shared core ({@code vance-addon-shared-simpleauth})
 * for {@code PermissionGrantService}.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = AnusSimpleAuthAddon.class)
public class AnusSimpleAuthAddon {
}
