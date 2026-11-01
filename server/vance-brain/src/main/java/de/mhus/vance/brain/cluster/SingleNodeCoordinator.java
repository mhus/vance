package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.cluster.ClusterMasterDocument;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The coordinator of the single-node edition: this pod is the only one that
 * works, so it is always the one that coordinates. No lease, no election.
 *
 * <p>A plain component rather than a conditional bean: the cloud edition
 * registers its own {@link ClusterCoordinator} as {@code @Primary}, which wins
 * the injection without depending on bean-definition order.
 */
@Component
public class SingleNodeCoordinator implements ClusterCoordinator {

    @Override
    public boolean isLocalPodMaster() {
        return true;
    }

    @Override
    public Optional<ClusterMasterDocument> currentLease() {
        return Optional.empty();
    }
}
