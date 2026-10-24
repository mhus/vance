package de.mhus.vance.shared.role;

import de.mhus.vance.shared.permission.PermissionProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link LocalPermissionProvider} unless another
 * {@link PermissionProvider} is present — an addon connecting a company
 * governor, or a test's recording provider.
 *
 * <p>An auto-configuration (listed in {@code AutoConfiguration.imports}) rather
 * than a component, because {@code @ConditionalOnMissingBean} is only reliable
 * once every user-defined bean is known; Spring Boot excludes this class from
 * the component scan of {@code de.mhus.vance.shared}. It also needs the role
 * storage itself: a narrow test slice that loads auto-configurations without
 * scanning {@code vance-shared} gets no provider rather than a broken one.
 */
@AutoConfiguration
public class LocalPermissionProviderConfiguration {

    @Bean
    @ConditionalOnBean(RoleAssignmentService.class)
    @ConditionalOnMissingBean(PermissionProvider.class)
    public PermissionProvider localPermissionProvider(RoleAssignmentService assignments) {
        return new LocalPermissionProvider(assignments);
    }
}
