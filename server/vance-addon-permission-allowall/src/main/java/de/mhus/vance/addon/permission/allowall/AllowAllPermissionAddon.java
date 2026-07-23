package de.mhus.vance.addon.permission.allowall;

import de.mhus.vance.shared.permission.PermissionResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Entry point of the allow-all permission provider addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports} whenever this JAR is on
 * the classpath (Brain bundle or anus context), and registers the single
 * {@link PermissionResolver} bean that satisfies the mandatory provider guard.
 */
@AutoConfiguration
public class AllowAllPermissionAddon {

    @Bean
    public PermissionResolver allowAllPermissionResolver() {
        return new AllowAllPermissionResolver();
    }
}
