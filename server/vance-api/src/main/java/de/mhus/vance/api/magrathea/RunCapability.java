package de.mhus.vance.api.magrathea;

/**
 * What a run is bound to, and therefore which task types it can execute.
 *
 * <p>A capability is a property of the <em>start</em>, not of the plan: the
 * same definition can run headless from a scheduler or bound to a person in
 * a session, and only the second one can ask that person a question. It is
 * also deliberately <em>stable for the lifetime of a run</em> — whether a
 * client happens to be connected right now is a snapshot that flips
 * constantly, and a plan whose runnability depended on it would sometimes
 * start and sometimes not for no reason the author can see.
 *
 * <p>Capabilities express "cannot", never "should not". They are checked
 * once at start ({@code MagratheaWorkflowService}) so a plan that can never
 * complete is refused where somebody is still watching, rather than dying
 * in state seven on a Wednesday. What a plan <em>ought</em> to contain for
 * its purpose is a matter for the authoring presets, not for validation.
 */
public enum RunCapability {

    /**
     * The run belongs to a session with a human owner — a non-system
     * session, whose {@code userId} is a real account.
     *
     * <p>Not "somebody is online": the wait itself is connection-independent
     * (an inbox item survives a closed laptop), so what matters is whether
     * there is anyone whose session this is at all.
     */
    USER_SESSION,

    /**
     * The run is owned by a ThinkProcess that waits for its result and
     * represents it outwards — the Vogon case.
     *
     * <p>Enables the two things a bare workflow cannot do: telling that
     * process the run is blocked, so it can raise the question where the
     * conversation is, and handing the result back into it when the run
     * ends.
     */
    OWNER_PROCESS
}
