package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
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
 * Installs a kit into a project for the first time. Fails when the
 * project already has an active kit — use {@code kit_update} to
 * replace the version of an installed kit, or remove the manifest
 * first.
 */
@Component
@RequiredArgsConstructor
public class KitInstallTool implements Tool {

    private final KitService kitService;

    @Override
    public String name() {
        return "kit_install";
    }

    @Override
    public String description() {
        return "Install a kit (skills, recipes, documents, settings, server-tools) "
                + "from a git repository into a project. Fails if the project already "
                + "has an active kit.";
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
                "description", "Vault passphrase needed when the kit ships PASSWORD-settings."));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("url"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_install requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProject(ctx,
                KitToolSupport.optionalString(params, "project"));
        KitImportRequestDto request = KitImportRequestDto.builder()
                .projectId(projectId)
                .source(KitToolSupport.sourceFrom(params))
                .token(KitToolSupport.optionalString(params, "token"))
                .vaultPassword(KitToolSupport.optionalString(params, "vault_password"))
                .mode(KitImportMode.INSTALL)
                .build();
        return KitToolSupport.resultToMap(
                kitService.importKit(ctx.tenantId(), request, ctx.userId()));
    }
}
