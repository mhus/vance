package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.brain.kit.KitLegacyMigrator;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Converts a project that still carries the pre-multi-kit
 * {@code _vance/kit-manifest.yaml} into an install record.
 *
 * <p>Relevant only for projects set up before kits became installable
 * side by side. Everywhere else it reports that there is nothing to do,
 * which is also the answer to "should I run this?".
 */
@Component
@RequiredArgsConstructor
public class KitMigrateLegacyTool implements Tool {

    private final KitService kitService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "kit_migrate_legacy";
    }

    @Override
    public String description() {
        return "Convert an old single-kit manifest (_vance/kit-manifest.yaml) into an "
                + "install record so the kit can be updated and managed like any other. "
                + "Reports that nothing is to be done when the project has no old manifest.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", Map.of(
                "type", "string",
                "description", "Project to migrate. Defaults to the current project."));
        properties.put("keep_as_kit_source", Map.of(
                "type", "boolean",
                "description",
                "Also mark the project as the source of this kit, i.e. keep it exportable. "
                        + "Default false — under the old model every tracked install wrote a "
                        + "manifest, so its presence does not mean anyone authors the kit here."));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of());
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Kit installation/management — Git-bundled project configuration";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_migrate_legacy requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProjectAuthorized(ctx,
                KitToolSupport.optionalString(params, "project"),
                permissionService, contextFactory, de.mhus.vance.shared.permission.Action.ADMIN);
        KitLegacyMigrator.Result result = kitService.migrateLegacy(
                ctx.tenantId(), projectId,
                KitToolSupport.optionalBoolean(params, "keep_as_kit_source"),
                ctx.userId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projectId);
        out.put("migrated", result.migrated());
        out.put("message", result.message());
        if (result.kitId() != null) out.put("kitId", result.kitId());
        if (result.migrated()) {
            out.put("counts", Map.of(
                    "documents", result.documents(),
                    "settings", result.settings()));
        }
        return out;
    }
}
