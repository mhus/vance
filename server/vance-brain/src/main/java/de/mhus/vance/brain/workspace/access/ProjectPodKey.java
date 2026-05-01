package de.mhus.vance.brain.workspace.access;

/** Cache key identifying a project that lives on a specific owner pod. */
public record ProjectPodKey(String tenantId, String projectName) {
}
