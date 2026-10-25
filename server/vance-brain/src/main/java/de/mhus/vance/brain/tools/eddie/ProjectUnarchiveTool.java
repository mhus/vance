package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectQuotaService;
import de.mhus.vance.shared.project.ProjectService;
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
 * Brings an archived project back — {@link ProjectLifecycleService#reopen},
 * the same path as {@code POST /admin/projects/{name}/unarchive}: active
 * project quota checked, group restored, placed like a new project with an
 * empty workspace. Gate is project ADMIN of the invoking user, checked
 * before the lookup so the answer reveals nothing to a caller without it;
 * like every administering tool it is refused in a process pinned to its own
 * project ({@link AdminToolSupport#actor}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectUnarchiveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name",
                            Map.of(
                                    "type", "string",
                                    "description", "Name of the archived project to bring back.")),
            "required", List.of("name"));

    /** Breaks the same bean cycle as {@link ProjectCreateTool}. */
    private final ObjectProvider<ProjectLifecycleService> lifecycleServiceProvider;

    private final ProjectService projectService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "project_unarchive";
    }

    @Override
    public String description() {
        return "Bring an archived project back to the active list, into the group "
                + "it was archived from. Documents, settings and sessions are all "
                + "there; the workspace starts empty (it was discarded on archive). "
                + "Fails when the tenant's active-project quota is reached.";
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
        return "restore an archived project; workspace starts empty";
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
        admin.enforce(actor, new Resource.Project(ctx.tenantId(), name), Action.ADMIN);
        if (projectService.findByTenantAndName(ctx.tenantId(), name).isEmpty()) {
            throw new ToolException("Project '" + name + "' not found");
        }

        ProjectDocument reopened;
        try {
            reopened = lifecycleServiceProvider.getObject().reopen(ctx.tenantId(), name);
        } catch (ProjectService.ProjectNotFoundException
                | ProjectService.ProjectStatusConflictException
                | ProjectQuotaService.ProjectQuotaExceededException e) {
            throw new ToolException(e.getMessage(), e);
        }
        log.info("project_unarchive: tenant='{}' project='{}' user='{}'", ctx.tenantId(), name, ctx.userId());

        admin.record(
                ctx,
                EddieActivityKind.PROJECT_UNARCHIVED,
                "Unarchived project `" + name + "`",
                List.of(EntityRef.project(name)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", reopened.getName());
        out.put("status", reopened.getStatus().name());
        out.put("projectGroupId", reopened.getProjectGroupId());
        out.put("workspaceEmpty", true);
        return out;
    }
}
