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
 * Splats a kit's content into the project without touching the
 * manifest. Used to import a colleague's kit ad-hoc; the result loses
 * its kit identity and cannot be updated or exported.
 */
@Component
@RequiredArgsConstructor
public class KitApplyTool implements Tool {

    private final KitService kitService;

    @Override
    public String name() {
        return "kit_apply";
    }

    @Override
    public String description() {
        return "Apply a kit's contents to the project without tracking it. "
                + "Existing files / tools are silently overwritten. Use "
                + "keep_passwords=true to preserve PASSWORD-settings the "
                + "user has already configured.";
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
        properties.put("keep_passwords", Map.of(
                "type", "boolean",
                "description",
                "Skip PASSWORD-settings in the kit so existing project credentials "
                        + "are preserved. Default false (overwrite)."));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("url"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_apply requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProject(ctx,
                KitToolSupport.optionalString(params, "project"));
        KitImportRequestDto request = KitImportRequestDto.builder()
                .projectId(projectId)
                .source(KitToolSupport.sourceFrom(params))
                .token(KitToolSupport.optionalString(params, "token"))
                .vaultPassword(KitToolSupport.optionalString(params, "vault_password"))
                .mode(KitImportMode.APPLY)
                .keepPasswords(KitToolSupport.optionalBoolean(params, "keep_passwords"))
                .build();
        return KitToolSupport.resultToMap(
                kitService.importKit(ctx.tenantId(), request, ctx.userId()));
    }
}
