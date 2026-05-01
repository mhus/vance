package de.mhus.vance.brain.project;

/**
 * Spring event — workspace is recovered, pod owns the project, engines
 * for it should now (re-)start. Listeners decide which engines (chat
 * processes, scheduled triggers, etc.). V1 has no listeners; the event
 * is the seam.
 */
public record ProjectEnginesStartRequested(String tenantId, String projectName) {
}
