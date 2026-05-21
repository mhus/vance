package de.mhus.vance.brain.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fallback {@link ClusterBringClient} for deployments where the
 * real HTTP-based implementation is absent — single-pod boots, tests,
 * or master-disabled clusters. Throws {@link ClusterBringException} on
 * every remote call so a misconfigured deployment surfaces clearly
 * instead of silently doing nothing.
 *
 * <p>Replaced at runtime by the real {@code HttpClusterBringClient} via
 * Spring's {@link ConditionalOnMissingBean} ordering — that one wins
 * whenever the cluster-token and HTTP client are wired in.
 */
@Configuration
@Slf4j
public class NoopClusterBringClient {

    @Bean
    @ConditionalOnMissingBean(ClusterBringClient.class)
    public ClusterBringClient noopClusterBringClient() {
        return new ClusterBringClient() {
            @Override
            public String requestBring(String endpoint, String tenantId, String projectName) {
                throw new ClusterBringException(
                        "No HTTP cluster-bring client configured — remote bring to '"
                                + endpoint + "' for '" + tenantId + "/" + projectName + "' refused");
            }

            @Override
            public SpawnResult requestSpawn(String masterEndpoint, String tenantId, String projectName) {
                throw new ClusterBringException(
                        "No HTTP cluster-bring client configured — remote spawn via '"
                                + masterEndpoint + "' for '" + tenantId + "/" + projectName + "' refused");
            }
        };
    }
}
