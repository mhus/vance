package de.mhus.vance.shared.workspace;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The project's work area: the folder on this machine and the snapshots a
 * suspend left in Mongo.
 *
 * <p>The one handler whose subject is not only rows. That has a consequence
 * worth knowing before running a delete from an admin shell: a workspace lives
 * on the disk of the pod that ran the project, so a shell on another machine
 * removes the snapshots and reports that it saw no folder. The folder is not
 * lost data in the way a document is — it is derived, and a running brain
 * disposes it as part of closing the project — but a report that stayed silent
 * would read as "there was nothing", which is a different statement.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceProjectDataHandler implements ProjectDataHandler {

    private final WorkspaceService workspaceService;
    private final MongoTemplate mongoTemplate;

    @Override
    public String id() {
        return "workspace";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(WorkspaceSnapshotDocument.class));
    }

    @Override
    public int order() {
        return 2300;
    }

    @Override
    public long count(String tenantId, String projectId) {
        long snapshots = workspaceService.snapshotsForProject(tenantId, projectId).size();
        return workspaceService.existsForProject(tenantId, projectId) ? snapshots + 1 : snapshots;
    }

    @Override
    public long delete(String tenantId, String projectId) {
        boolean hadFolder = workspaceService.existsForProject(tenantId, projectId);
        long snapshots = workspaceService.deleteForProject(tenantId, projectId);
        return hadFolder ? snapshots + 1 : snapshots;
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        return workspaceService.renameProject(tenantId, projectId, newProjectId);
    }

    /**
     * Refuses when a folder already sits under the new name.
     *
     * <p>Asked before anything is written, which is the only moment it helps:
     * discovering this halfway through a rename would leave the tenant with its
     * documents under the new name and its work area under the old one.
     */
    @Override
    public @Nullable String renameBlocker(
            String tenantId, String projectId, String newProjectId) {
        if (workspaceService.existsForProject(tenantId, projectId)
                && workspaceService.existsForProject(tenantId, newProjectId)) {
            return "a workspace folder for '" + newProjectId
                    + "' already exists on this machine — merging two work areas is not a rename";
        }
        return null;
    }
}
