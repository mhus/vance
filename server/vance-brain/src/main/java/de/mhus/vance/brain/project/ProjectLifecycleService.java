package de.mhus.vance.brain.project;

import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Orchestrator for the project lifecycle (see {@code
 * specification/workspace-management.md} §11). Wraps three steps:
 *
 * <ul>
 *   <li>{@link #bring} — claim pod, recover workspace, mark RUNNING
 *       (and request engines start).</li>
 *   <li>{@link #suspend} — request engines stop, suspend workspace,
 *       mark SUSPENDED.</li>
 *   <li>{@link #close} — dispose workspace, mark CLOSED, move to the
 *       archived group.</li>
 * </ul>
 *
 * <p>Engine start/stop is signalled via Spring events
 * ({@link ProjectEnginesStartRequested} / {@link ProjectEnginesStopRequested}).
 * V1 has no listeners — engine cleanup is operator-driven. Listeners
 * land with the session-lifecycle work.
 *
 * <p>Crash recovery: every transition is idempotent. {@link #bring}
 * re-runs through RECOVERING from any non-CLOSED status; {@link #suspend}
 * picks up from SUSPENDING.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectLifecycleService {

    private final ProjectService projectService;
    private final ProjectManagerService projectManager;
    private final WorkspaceService workspaceService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Move a project on this pod from any non-CLOSED status to RUNNING:
     * claim the pod, transition to RECOVERING, restore the workspace
     * (auto-recovers from snapshots if any), publish
     * {@link ProjectEnginesStartRequested}, transition to RUNNING.
     * Idempotent — repeated calls on a RUNNING project just refresh
     * the pod claim.
     */
    public ProjectDocument bring(String tenantId, String projectName) {
        ProjectDocument doc = projectManager.claimForLocalPod(tenantId, projectName);
        if (doc.getStatus() == ProjectStatus.RUNNING) {
            log.debug("Project '{}/{}' already RUNNING — claim refreshed", tenantId, projectName);
            return doc;
        }
        ProjectStatus from = doc.getStatus();
        doc = projectService.transitionStatus(tenantId, projectName, from, ProjectStatus.RECOVERING);
        try {
            workspaceService.init(tenantId, projectName);
        } catch (RuntimeException e) {
            log.error("Workspace init failed for '{}/{}' (status remains RECOVERING): {}",
                    tenantId, projectName, e.toString());
            throw e;
        }
        eventPublisher.publishEvent(new ProjectEnginesStartRequested(tenantId, projectName));
        doc = projectService.transitionStatus(
                tenantId, projectName, ProjectStatus.RECOVERING, ProjectStatus.RUNNING);
        log.info("Project '{}/{}' brought to RUNNING (was {})", tenantId, projectName, from);
        return doc;
    }

    /**
     * Move a RUNNING project to SUSPENDED: transition to SUSPENDING,
     * publish {@link ProjectEnginesStopRequested}, suspend the workspace
     * (snapshots → Mongo, folder gone), transition to SUSPENDED. Picks
     * up from SUSPENDING if a previous attempt crashed mid-flow.
     * Idempotent on already-SUSPENDED projects.
     */
    public ProjectDocument suspend(String tenantId, String projectName) {
        ProjectDocument doc = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '" + tenantId + "'"));
        switch (doc.getStatus()) {
            case SUSPENDED -> {
                log.debug("Project '{}/{}' already SUSPENDED", tenantId, projectName);
                return doc;
            }
            case CLOSED -> throw new ProjectService.ProjectStatusConflictException(
                    "Project '" + projectName + "' is CLOSED — cannot suspend");
            case SUSPENDING -> log.info(
                    "Project '{}/{}' was in SUSPENDING (prior crash) — completing suspend",
                    tenantId, projectName);
            default -> {
                doc = projectService.transitionStatus(
                        tenantId, projectName, doc.getStatus(), ProjectStatus.SUSPENDING);
            }
        }
        eventPublisher.publishEvent(new ProjectEnginesStopRequested(tenantId, projectName));
        try {
            workspaceService.suspendAll(projectName);
        } catch (RuntimeException e) {
            log.error("Workspace suspendAll failed for '{}/{}' (status remains SUSPENDING): {}",
                    tenantId, projectName, e.toString());
            throw e;
        }
        doc = projectService.transitionStatus(
                tenantId, projectName, ProjectStatus.SUSPENDING, ProjectStatus.SUSPENDED);
        log.info("Project '{}/{}' suspended", tenantId, projectName);
        return doc;
    }

    /**
     * Terminate a project: dispose the workspace (folder + snapshots
     * gone), then mark CLOSED and move to {@code closedGroupId}.
     * Refuses SYSTEM-kind projects (delegated check via
     * {@link ProjectService#close}). Engine teardown is the caller's
     * responsibility — close does not emit
     * {@link ProjectEnginesStopRequested} (use {@link #suspend} first
     * if needed).
     */
    public ProjectDocument close(String tenantId, String projectName, String closedGroupId) {
        workspaceService.dispose(projectName);
        ProjectDocument doc = projectService.close(tenantId, projectName, closedGroupId);
        log.info("Project '{}/{}' closed → group '{}'", tenantId, projectName, closedGroupId);
        return doc;
    }
}
