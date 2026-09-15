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
 * Read-only static check of a designer-app folder: reports what the app's
 * catalogue silently drops — folders with files but no {@code index.html}
 * (the classic authoring miss), broken {@code design.yaml} metadata,
 * ghost entries in the manifest's {@code designer.order}, and a missing
 * manifest. Use it after creating or editing designs to self-check
 * before telling the user it's done.
 */
@Component
@Slf4j
public class DesignerValidateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put("folder", Map.of("type", "string", "description", "The designer-app folder, e.g. 'designs'."));
                    put("projectId", Map.of("type", "string"));
                }
            },
            "required",
            List.of("folder"));

    private final EddieContext eddieContext;
    private final DesignerValidationService validationService;

    public DesignerValidateTool(EddieContext eddieContext, DesignerValidationService validationService) {
        this.eddieContext = eddieContext;
        this.validationService = validationService;
    }

    @Override
    public String name() {
        return "designer_validate";
    }

    @Override
    public String description() {
        return "Statically validate a designer-app folder: reports folders with "
                + "files but no index.html (invisible in the app), broken design.yaml "
                + "metadata, ghost entries in the manifest's designer.order, and a "
                + "missing _app.yaml. Read-only. Returns "
                + "{ ok, errors, warnings, findings[] }. Run it after creating or "
                + "editing designs.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "read", "document", "designer", "validate");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = params.get("folder") instanceof String s && !s.isBlank() ? s.trim() : null;
        if (folder == null) throw new ToolException("folder is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        String normalised;
        try {
            normalised = DesignerPaths.normaliseFolder(folder);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }

        DesignerValidationService.Result result =
                validationService.validate(ctx.tenantId(), project.getName(), normalised);
        return result.toMap();
    }
}
