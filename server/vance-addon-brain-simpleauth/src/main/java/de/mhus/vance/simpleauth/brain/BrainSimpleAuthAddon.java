package de.mhus.vance.simpleauth.brain;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Brain-side Simple-Auth management addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans the
 * package so the grant admin controller and the {@code permission_grant_*}
 * tools register. Requires the shared-level core ({@code vance-addon-simpleauth})
 * to be present too — it provides the {@code PermissionGrantService} these
 * surfaces consume.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = BrainSimpleAuthAddon.class)
public class BrainSimpleAuthAddon {
}
