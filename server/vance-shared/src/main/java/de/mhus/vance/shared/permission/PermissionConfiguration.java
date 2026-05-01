package de.mhus.vance.shared.permission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the default {@link PermissionResolver} when no other bean of that
 * type is present. This is the recommended Spring-Boot fallback pattern —
 * downstream applications add their own {@code @Bean PermissionResolver} (or
 * {@code @Component}) and this default steps aside.
 */
@Configuration
public class PermissionConfiguration {

    @Bean
    @ConditionalOnMissingBean(PermissionResolver.class)
    public PermissionResolver allowAllPermissionResolver() {
        return new AllowAllPermissionResolver();
    }
}
