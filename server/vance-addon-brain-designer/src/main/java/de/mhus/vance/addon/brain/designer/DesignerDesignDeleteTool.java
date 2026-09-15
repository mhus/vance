package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Deletes one design of a designer app — every document under
 * {@code <folder>/<name>/} moves to the trash in one call, the same
 * recoverable delete the app's delete button performs. Without this tool
 * an agent would have to find and trash each file individually, and a
 * partial run would leave a broken half-design behind.
 */
@Component
@Slf4j
public class DesignerDesignDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put("folder", Map.of("type", "string", "description", "The designer-app folder, e.g. 'designs'."));
                    put(
                            "name",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "The design folder name to delete, "
                                            + "e.g. 'landing'. Moves ALL its files to the trash."));
                    put("projectId", Map.of("type", "string"));
                }
            },
            "required",
            List.of("folder", "name"));

    private final EddieContext eddieContext;
    private final DesignerApplication designerApplication;

    public DesignerDesignDeleteTool(EddieContext eddieContext, DesignerApplication designerApplication) {
        this.eddieContext = eddieContext;
        this.designerApplication = designerApplication;
    }

    @Override
    public String name() {
        return "designer_design_delete";
    }

    @Override
    public String description() {
        return "Delete one design of a designer app: every document under "
                + "<folder>/<name>/ moves to the trash (recoverable). Same effect "
                + "as the app's delete button. Use it when the user asks to remove "
                + "or replace a design — do NOT trash the files one by one.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "designer", "delete");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = params.get("folder") instanceof String s && !s.isBlank() ? s.trim() : null;
        String name = params.get("name") instanceof String s2 && !s2.isBlank() ? s2.trim() : null;
        if (folder == null) throw new ToolException("folder is required");
        if (name == null) throw new ToolException("name is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        int deleted = designerApplication.deleteDesign(ctx.tenantId(), project.getName(), folder, name, ctx.userId());

        log.info(
                "DesignerDesignDeleteTool tenant='{}' folder='{}' design='{}' files={}",
                ctx.tenantId(),
                folder,
                name,
                deleted);
        return Map.of(
                "deleted", deleted,
                "name", name,
                "note", "Files moved to the trash — recoverable from there.");
    }
}
