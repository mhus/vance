package de.mhus.vance.shared.project;

/**
 * What the pod holding a project is doing with it right now — the
 * <em>actual</em> counterpart of the desired {@link ProjectStatus}.
 *
 * <p>Written only by the pod that holds the lease, and valid only while that
 * lease lives. A pod that dies cannot write {@link #STOPPED}, so a stored
 * value without a live lease means nothing: read it through
 * {@link ProjectOwnership#effectiveActualState}, never from the field.
 */
public enum ProjectActualState {

    /** Not running anywhere. */
    STOPPED,

    /** A pod is bringing the project up: workspace, RAG, engines. Transient. */
    STARTING,

    /** The lease holder serves the project. */
    RUNNING,

    /** The lease holder is taking the project down. Transient. */
    STOPPING
}
