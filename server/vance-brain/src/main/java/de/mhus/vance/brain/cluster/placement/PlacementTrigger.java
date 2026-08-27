package de.mhus.vance.brain.cluster.placement;

/**
 * Who is asking for a placement. The trigger exists for exactly one
 * decision — whether this pod may prefer itself — and carries it as
 * {@link #prefersLocal()} instead of leaving it to the caller.
 *
 * <p>Before this, each call site brought its own answer: two identical
 * copies of a {@code haveLocalRoom()} helper plus a fallback chain that
 * ended in "bring locally" even when capacity said no
 * ({@code planning/project-placement-labels.md} §1).
 */
public enum PlacementTrigger {

    /**
     * A project was just created and wants to run. Prefers local: the
     * caller is on the pod the user is talking to, and a network hop for
     * a project that fits right here buys nothing.
     */
    CREATE(true),

    /**
     * Something asked where a project lives and it turned out to live
     * nowhere ({@code ProjectLocator} with {@code autoStart}). Same
     * reasoning as {@link #CREATE}.
     */
    LOCATE(true),

    /**
     * The Cluster-Master distributor is placing projects nobody owns.
     * Deliberately <em>not</em> local: distributing means spreading, and
     * a master that preferred itself would collect the whole cluster.
     */
    DISTRIBUTOR(false),

    /**
     * Another pod asked this one to place a project on its behalf
     * ({@code POST /internal/cluster/master/spawn}). Not local for the
     * same reason as {@link #DISTRIBUTOR} — the request is "put it
     * somewhere sensible", not "put it on you".
     */
    REMOTE_REQUEST(false),

    /**
     * An operator or an external controller asked for a project to be placed
     * ({@code POST /internal/cluster/place}). Not local, and that is the whole
     * difference to {@code POST /admin/projects/{name}/resume}: resume means
     * "start it <em>here</em>" and brings it up on whichever pod answered the
     * call, this means "start it where it belongs" and lets the labels decide.
     */
    ADMIN(false);

    private final boolean prefersLocal;

    PlacementTrigger(boolean prefersLocal) {
        this.prefersLocal = prefersLocal;
    }

    /**
     * Whether this pod may short-circuit to itself when it has room,
     * skipping the load comparison across the cluster.
     */
    public boolean prefersLocal() {
        return prefersLocal;
    }
}
