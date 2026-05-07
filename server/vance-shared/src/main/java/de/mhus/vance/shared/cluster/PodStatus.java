package de.mhus.vance.shared.cluster;

/**
 * Self-reported status a brain pod writes onto its own
 * {@link BrainPodDocument}. Stale-detection is observer-side, derived
 * from {@link BrainPodDocument#getLastHeartbeatAt()} and not part of
 * this enum — STALE is a beholder's verdict, not the pod's confession.
 */
public enum PodStatus {

    /** Spring is booting, beans are coming up; not yet accepting work. */
    STARTING,

    /** {@code ApplicationReadyEvent} fired; ready to claim projects. */
    RUNNING,

    /** Graceful shutdown in progress; finishing in-flight work. */
    STOPPING,

    /** Shutdown completed cleanly. */
    STOPPED
}
