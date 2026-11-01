package de.mhus.vance.shared.project;

/**
 * Lifecycle state of a {@link ProjectDocument}.
 *
 * <pre>
 *   INIT ──claim──► RECOVERING ──recover──► RUNNING ──suspend──► SUSPENDING ──► SUSPENDED
 *                                              │                                    │
 *                                              └────────── close ───────────────────┴──► CLOSED
 *                                                                                  ▲
 *                                              SUSPENDED ──claim──► RECOVERING ────┘ (resumes via init)
 * </pre>
 *
 * <p>{@code RECOVERING} and {@code SUSPENDING} are transient — a healthy
 * project never stays there long. If a pod crashes mid-transition, the
 * next pod taking over the project re-runs the recover or suspend pass
 * to converge.
 *
 * <p>Pod-affinity (which pod owns the project) is orthogonal to the
 * lifecycle status, tracked via {@link ProjectDocument#getHomeNode()}.
 */
public enum ProjectStatus {

    /** Newly created, never recovered, no on-disk workspace yet. */
    INIT,

    /** A pod is bringing the project up. Transient. */
    RECOVERING,

    /** The pod actively serves the project. */
    RUNNING,

    /** Suspend in progress: engines stopping. Transient. */
    SUSPENDING,

    /** Stopped on purpose; engines are down, the workspace folder is untouched. Resumable via {@code init}. */
    SUSPENDED,

    /** Terminal — assets retained per policy, no further serving. */
    CLOSED
}
