package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitManifestDto;
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
 * Re-runs the active kit against its origin (or against an overridden
 * source). Refreshes documents/settings/tools and rewrites the
 * manifest. {@code prune=true} additionally deletes artefacts that
 * were tracked previously but are absent in the new kit version.
 */
@Component
@RequiredArgsConstructor
public class KitUpdateTool implements Tool {

    private final KitService kitService;

    @Override
    public String name() {
        return "kit_update";
    }

    @Override
    public String description() {
        return "Update the project's active kit by re-fetching its source. "
                + "Override url/path/branch/commit when the manifest origin "
                + "needs to change. `prune=true` removes artefacts no longer "
                + "in the kit.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> properties = new LinkedHashMap<>(KitToolSupport.sourceSchemaProps());
        properties.put("vault_password", Map.of(
                "type", "string",
                "description", "Vault passphrase needed when PASSWORD-settings are touched."));
        properties.put("prune", Map.of(
                "type", "boolean",
                "description",
                "Delete artefacts tracked in the previous manifest but missing in the new kit. "
                        + "Default false (artefacts only drop out of the manifest)."));
        // url is optional for update — falls back to manifest.origin.
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of());
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_update requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProject(ctx,
                KitToolSupport.optionalString(params, "project"));
        KitInheritDto source = sourceWithFallback(
                ctx.tenantId(), projectId, params);
        KitImportRequestDto request = KitImportRequestDto.builder()
                .projectId(projectId)
                .source(source)
                .token(KitToolSupport.optionalString(params, "token"))
                .vaultPassword(KitToolSupport.optionalString(params, "vault_password"))
                .mode(KitImportMode.UPDATE)
                .prune(KitToolSupport.optionalBoolean(params, "prune"))
                .build();
        return KitToolSupport.resultToMap(
                kitService.importKit(ctx.tenantId(), request, ctx.userId()));
    }

    private KitInheritDto sourceWithFallback(
            String tenantId, String projectId, Map<String, Object> params) {
        String url = KitToolSupport.optionalString(params, "url");
        String path = KitToolSupport.optionalString(params, "path");
        String branch = KitToolSupport.optionalString(params, "branch");
        String commit = KitToolSupport.optionalString(params, "commit");
        if (url == null) {
            KitManifestDto manifest = kitService.status(tenantId, projectId);
            if (manifest == null) {
                throw new ToolException("kit_update needs `url` or an active manifest");
            }
            url = manifest.getOrigin().getUrl();
            if (path == null) path = manifest.getOrigin().getPath();
            if (branch == null) branch = manifest.getOrigin().getBranch();
        }
        return KitInheritDto.builder()
                .url(url).path(path).branch(branch).commit(commit).build();
    }
}
