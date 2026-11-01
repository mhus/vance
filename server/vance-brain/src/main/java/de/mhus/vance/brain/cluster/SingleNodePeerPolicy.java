package de.mhus.vance.brain.cluster;

import org.springframework.stereotype.Component;

/**
 * Single-node edition: every pod but this one is stale. Running two community
 * brains against one database is not supported.
 *
 * <p>A plain component; the cloud edition's {@code @Primary} policy replaces it.
 */
@Component
public class SingleNodePeerPolicy implements ClusterPeerPolicy {

    @Override
    public boolean countsPeers() {
        return false;
    }
}
