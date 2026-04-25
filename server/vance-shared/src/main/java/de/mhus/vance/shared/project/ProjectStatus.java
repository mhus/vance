package de.mhus.vance.shared.project;

/**
 * Lifecycle state of a {@link ProjectDocument}.
 *
 * <p>The flow is:
 * <pre>
 *   PENDING ──claim──► ACTIVE ──suspend──► SUSPENDED ──resume──► ACTIVE
 *                          │                                │
 *                          └────────── archive ─────────────┴──► ARCHIVED
 * </pre>
 *
 * <p>v1 uses only {@link #PENDING} and {@link #ACTIVE} — the suspend/resume
 * and archive transitions are scaffolded in the data model so the
 * {@code ProjectManagerService} contract is stable, but the orchestration
 * for them lands later.
 */
public enum ProjectStatus {

    /** Newly created, no pod has claimed the project yet. */
    PENDING,

    /** A pod owns the project and serves its sessions. */
    ACTIVE,

    /** Reserved — temporarily not served, but not deleted. */
    SUSPENDED,

    /** Reserved — terminal state; assets retained read-only or scheduled for cleanup. */
    ARCHIVED
}
