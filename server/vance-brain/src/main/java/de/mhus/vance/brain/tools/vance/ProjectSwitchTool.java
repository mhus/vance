package de.mhus.vance.brain.tools.vance;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.vance.activity.EntityRef;
import de.mhus.vance.brain.vance.activity.VanceActivityKind;
import de.mhus.vance.brain.vance.activity.VanceActivityService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Sets Vance's active project — the implicit target for subsequent
 * doc / team / inbox tool calls that don't pass a {@code projectId}
 * explicitly. Active project survives across turns of the same Vance
 * process (persisted in the scratchpad slot
 * {@value VanceContext#ACTIVE_PROJECT_SLOT}).
 */
@Component
@RequiredArgsConstructor
public class ProjectSwitchTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Project name to switch to. "
                                    + "Use project_list to see what's available.")),
            "required", List.of("name"));

    private final ProjectService projectService;
    private final VanceContext vanceContext;
    private final VanceActivityService activityService;

    @Override
    public String name() {
        return "project_switch";
    }

    @Override
    public String description() {
        return "Switch the active project context. Subsequent tools "
                + "(doc_*, team_*, inbox_post) default to this project "
                + "until you switch again.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("name");
        if (!(raw instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        ProjectDocument project = projectService.findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException(
                        "Project '" + name + "' not found in tenant '"
                                + ctx.tenantId() + "'"));
        if (project.getKind() == ProjectKind.SYSTEM) {
            throw new ToolException(
                    "Project '" + name + "' is SYSTEM — content operations "
                            + "are not supported there. Pick a regular user project.");
        }
        vanceContext.writeActiveProject(ctx, project.getName());

        if (ctx.userId() != null && ctx.processId() != null) {
            activityService.append(
                    ctx.tenantId(), ctx.userId(),
                    ctx.sessionId(), ctx.processId(),
                    VanceActivityKind.PROJECT_SWITCHED,
                    "Aktives Projekt: `" + project.getName() + "`",
                    List.of(EntityRef.project(project.getName())));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", project.getName());
        if (project.getTitle() != null) {
            out.put("title", project.getTitle());
        }
        out.put("status", project.getStatus() == null ? "" : project.getStatus().name());
        return out;
    }
}
