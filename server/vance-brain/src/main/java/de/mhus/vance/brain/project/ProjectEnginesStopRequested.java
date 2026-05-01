package de.mhus.vance.brain.project;

/**
 * Spring event — engines for the project should stop, workspace is
 * about to go off-disk. Listeners (V2) coordinate engine teardown.
 */
public record ProjectEnginesStopRequested(String tenantId, String projectName) {
}
