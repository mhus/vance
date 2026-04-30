package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Pushes the project's active kit (top-layer) back to its origin
 * repository or to an overridden target. Inherits are referenced via
 * the kit.yaml descriptor — they are not re-emitted as files.
 */
@Component
@RequiredArgsConstructor
public class KitExportTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", Map.of("type", "string",
                "description", "Project to export from. Defaults to the current project."));
        properties.put("url", Map.of("type", "string",
                "description", "Target repo URL. Defaults to manifest.origin.url."));
        properties.put("path", Map.of("type", "string",
                "description", "Sub-path inside the repo. Defaults to manifest.origin.path."));
        properties.put("branch", Map.of("type", "string",
                "description", "Branch to push. Defaults to manifest.origin.branch."));
        properties.put("token", Map.of("type", "string",
                "description", "Auth token for HTTPS pushes."));
        properties.put("vault_password", Map.of("type", "string",
                "description",
                "Vault passphrase used to re-encrypt PASSWORD-settings into the export."));
        properties.put("commit_message", Map.of("type", "string",
                "description", "Commit message. Defaults to vance-export: <kit>@<sha>."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of());
    }

    private final KitService kitService;

    @Override
    public String name() {
        return "kit_export";
    }

    @Override
    public String description() {
        return "Push the project's active kit back to a git repository. "
                + "Only the top-layer artefacts (per kit-manifest) are written; "
                + "inherits are referenced via kit.yaml.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_export requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProject(ctx,
                KitToolSupport.optionalString(params, "project"));
        KitExportRequestDto request = KitExportRequestDto.builder()
                .projectId(projectId)
                .url(KitToolSupport.optionalString(params, "url"))
                .path(KitToolSupport.optionalString(params, "path"))
                .branch(KitToolSupport.optionalString(params, "branch"))
                .token(KitToolSupport.optionalString(params, "token"))
                .vaultPassword(KitToolSupport.optionalString(params, "vault_password"))
                .commitMessage(KitToolSupport.optionalString(params, "commit_message"))
                .build();
        return KitToolSupport.resultToMap(
                kitService.export(ctx.tenantId(), request, ctx.userId()));
    }
}
