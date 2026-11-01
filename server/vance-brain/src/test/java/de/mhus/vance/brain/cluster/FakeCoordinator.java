package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.cluster.ClusterMasterDocument;
import java.util.Optional;

/**
 * A {@link ClusterCoordinator} with settable answers, for tests of the jobs
 * that ask it. A Mockito mock would answer {@code false} to the default
 * methods and hide exactly the distinction those tests are about.
 */
public final class FakeCoordinator implements ClusterCoordinator {

    private boolean master;
    private boolean coordinated;

    private FakeCoordinator(boolean master, boolean coordinated) {
        this.master = master;
        this.coordinated = coordinated;
    }

    /** The coordinator itself — the single-node edition, or the elected pod. */
    public static FakeCoordinator master() {
        return new FakeCoordinator(true, true);
    }

    /** Coordination is on and another pod holds it. */
    public static FakeCoordinator notMaster() {
        return new FakeCoordinator(false, true);
    }

    /** The election is switched off: nobody coordinates. */
    public static FakeCoordinator uncoordinated() {
        return new FakeCoordinator(false, false);
    }

    public void setMaster(boolean master) {
        this.master = master;
    }

    @Override
    public boolean isLocalPodMaster() {
        return master;
    }

    @Override
    public boolean isCoordinated() {
        return coordinated;
    }

    @Override
    public Optional<ClusterMasterDocument> currentLease() {
        return Optional.empty();
    }
}
