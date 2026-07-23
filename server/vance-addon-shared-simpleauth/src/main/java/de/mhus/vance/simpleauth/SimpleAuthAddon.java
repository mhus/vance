package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.PermissionResolver;
import de.mhus.vance.shared.team.TeamService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Entry point of the Simple-Auth permission provider addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports} whenever the JAR is on
 * the classpath (Brain bundle or anus context).
 *
 * <p>Component-scans the addon package so {@link PermissionGrantService},
 * {@link SimpleAuthPermissionBootstrap} and the (conditional) web/anus surfaces
 * register; enables the addon's own Mongo repository; and wires the single
 * {@link PermissionResolver} bean that satisfies the mandatory-provider guard.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = SimpleAuthAddon.class)
@EnableMongoRepositories(basePackageClasses = PermissionGrantRepository.class)
public class SimpleAuthAddon {

    @Bean
    public PermissionResolver mongoPermissionResolver(
            PermissionGrantService grants, TeamService teamService,
            ObjectProvider<MetricService> metricService) {
        // MetricService is optional — the anus context has no MeterRegistry.
        return new MongoPermissionResolver(
                grants, teamService, metricService.getIfAvailable());
    }
}
