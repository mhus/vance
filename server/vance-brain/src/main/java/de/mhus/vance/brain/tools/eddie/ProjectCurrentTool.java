package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Returns Eddie's current active project (or {@code active=null} if
 * none is set yet). Useful as a discovery / status call so Eddie can
 * remind the user what context they're working in, e.g. when answering
 * „woran arbeitest du gerade?".
 */
@Component
@RequiredArgsConstructor
public class ProjectCurrentTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final EddieContext eddieContext;
    private final ProjectService projectService;

    @Override
    public String name() {
        return "project_current";
    }

    @Override
    public String description() {
        return "Return the currently active project (set by project_switch). "
                + "Result {active: null} when no project is selected.";
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
    public Set<String> labels() {
        return Set.of("eddie", "read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        // Resolution order mirrors EddieContext.resolveProject:
        // Eddie hub-process active-slot (set by project_switch) →
        // ctx.projectId() (session-bound project for Arthur/Ford foot
        // sessions, where there is no switch step) → null. Without
        // the ctx fallback, this tool reports "no project active"
        // even though the LLM is clearly operating inside one.
        String name = eddieContext.readActiveProject(ctx)
                .orElseGet(() -> {
                    String bound = ctx.projectId();
                    return (bound == null || bound.isBlank()) ? null : bound;
                });
        Map<String, Object> out = new LinkedHashMap<>();
        if (name == null) {
            out.put("active", null);
            return out;
        }
        Optional<ProjectDocument> project =
                projectService.findByTenantAndName(ctx.tenantId(), name);
        if (project.isEmpty()) {
            // Slot points to a project that vanished — surface that
            // so Eddie can apologise / pick a new one.
            out.put("active", name);
            out.put("stale", true);
            return out;
        }
        ProjectDocument p = project.get();
        out.put("active", p.getName());
        if (p.getTitle() != null) {
            out.put("title", p.getTitle());
        }
        out.put("status", p.getStatus() == null ? "" : p.getStatus().name());
        out.put("kind", p.getKind() == null ? "" : p.getKind().name());
        if (p.getProjectGroupId() != null) {
            out.put("projectGroupId", p.getProjectGroupId());
        }
        return out;
    }
}
