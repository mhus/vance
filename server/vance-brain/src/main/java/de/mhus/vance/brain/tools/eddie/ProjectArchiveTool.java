package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Archives a user project — the same act as the settings panel's "Archive"
 * button ({@code DELETE /admin/projects/{name}}): status CLOSED, moved to the
 * reserved {@code archived} group, workspace and project RAG discarded.
 * Documents, settings, sessions and roles stay; {@code project_unarchive}
 * brings it back with an empty workspace.
 *
 * <p>Because the workspace loss cannot be undone, the call is fenced:
 * <ul>
 *   <li>project ADMIN of the invoking user — the REST gate, checked before
 *       the project is even looked up, so a caller without the right learns
 *       nothing about which projects exist or how they stand;</li>
 *   <li>never a SYSTEM project, never the calling process's own project
 *       (a process would discard the workspace it runs in), and not from a
 *       process pinned to its own project (workers, confined engines);</li>
 *   <li>{@code confirmName} must repeat the name — the tool-side analogue of
 *       the typed name in the maintenance shell, against a hallucinated or
 *       mixed-up project name.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectArchiveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name",
                                    Map.of(
                                            "type", "string",
                                            "description", "Name of the project to archive."),
                            "confirmName",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "The same project name again. Only set it after the "
                                                    + "user has confirmed archiving this project.")),
            "required", List.of("name", "confirmName"));

    /** Breaks the same bean cycle as {@link ProjectCreateTool}. */
    private final ObjectProvider<ProjectLifecycleService> lifecycleServiceProvider;

    private final ProjectService projectService;
    private final ProjectGroupService projectGroupService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "project_archive";
    }

    @Override
    public String description() {
        return "Archive a project: it leaves the active list and moves to the "
                + "'archived' group. Its workspace (checkouts, working files) and "
                + "its RAG index are DISCARDED and cannot be recovered; documents, "
                + "settings, sessions and roles are kept. Ask the user first, then "
                + "pass the name twice. project_unarchive brings it back with an "
                + "empty workspace.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "archive a project; discards its workspace, keeps documents";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, ToolLabels.EXECUTIVE, ToolLabels.WRITE);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        SecurityContext actor = admin.actor(ctx, name());
        String name = AdminToolSupport.req(params, "name");
        AdminToolSupport.requireConfirmed(name, AdminToolSupport.req(params, "confirmName"), "project");
        if (name.equals(ctx.projectId())) {
            throw new ToolException(
                    "Project '" + name + "' is the project this process runs in — it cannot archive itself");
        }
        admin.enforce(actor, new Resource.Project(ctx.tenantId(), name), Action.ADMIN);
        ProjectDocument project = projectService
                .findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException("Project '" + name + "' not found"));
        if (project.getKind() == ProjectKind.SYSTEM) {
            throw new ToolException("Project '" + name + "' is a system project — cannot archive");
        }
        if (project.getStatus() == ProjectStatus.CLOSED) {
            throw new ToolException("Project '" + name + "' is already archived");
        }

        String archivedGroup =
                projectGroupService.ensureArchivedGroup(ctx.tenantId()).getName();
        ProjectDocument closed;
        try {
            closed = lifecycleServiceProvider.getObject().close(ctx.tenantId(), name, archivedGroup);
        } catch (ProjectService.ProjectNotFoundException | ProjectService.SystemProjectProtectedException e) {
            throw new ToolException(e.getMessage(), e);
        }
        log.info("project_archive: tenant='{}' project='{}' user='{}'", ctx.tenantId(), name, ctx.userId());

        admin.record(
                ctx,
                EddieActivityKind.PROJECT_ARCHIVED,
                "Archived project `" + name + "`",
                List.of(EntityRef.project(name)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", closed.getName());
        out.put("status", closed.getStatus().name());
        out.put("projectGroupId", closed.getProjectGroupId());
        if (closed.getArchivedFromGroupId() != null) {
            out.put("archivedFromGroupId", closed.getArchivedFromGroupId());
        }
        out.put("workspaceDiscarded", true);
        return out;
    }
}
