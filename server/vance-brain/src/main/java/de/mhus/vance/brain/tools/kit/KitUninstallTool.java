package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitInstalledRecordDto;
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
 * Removes one installed kit from a project.
 *
 * <p>Only meaningful now that a project may hold several kits — before,
 * "uninstall" and "install a different kit" were the same act.
 *
 * <p>The default is non-destructive: the record goes, the artefacts
 * stay, because the user has very likely built on them.
 * {@code prune=true} removes the artefacts too — except those another
 * installed kit also owns, which would otherwise silently strip a kit
 * nobody touched.
 */
@Component
@RequiredArgsConstructor
public class KitUninstallTool implements Tool {

    private final KitService kitService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "kit_uninstall";
    }

    @Override
    public String description() {
        return "Remove an installed kit from the project. By default only the install "
                + "record goes and the files stay; `prune=true` also deletes the artefacts "
                + "the kit contributed (never those another installed kit owns too).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kit", Map.of(
                "type", "string",
                "description",
                "Record id or name of the installed kit — see kit_status."));
        properties.put("project", Map.of(
                "type", "string",
                "description", "Project to act on. Defaults to the current project."));
        properties.put("prune", Map.of(
                "type", "boolean",
                "description",
                "Also delete the documents and settings the kit installed. Default false."));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("kit"));
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive", "destructive");
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
            throw new ToolException("kit_uninstall requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProjectAuthorized(ctx,
                KitToolSupport.optionalString(params, "project"),
                permissionService, contextFactory, de.mhus.vance.shared.permission.Action.ADMIN);
        String kitRef = KitToolSupport.requireString(params, "kit");
        boolean prune = KitToolSupport.optionalBoolean(params, "prune");
        return KitToolSupport.resultToMap(kitService.uninstall(
                ctx.tenantId(), projectId, resolveKitId(ctx.tenantId(), projectId, kitRef), prune));
    }

    /** Accept the record id or the display name — {@code kit_status} shows both. */
    private String resolveKitId(String tenantId, String projectId, String kitRef) {
        List<KitInstalledRecordDto> installed = kitService.status(tenantId, projectId);
        for (KitInstalledRecordDto record : installed) {
            if (record.getId().equals(kitRef)) return record.getId();
        }
        List<KitInstalledRecordDto> byName = installed.stream()
                .filter(r -> r.getKit().getName().equals(kitRef))
                .toList();
        if (byName.size() == 1) return byName.get(0).getId();
        if (byName.size() > 1) {
            throw new ToolException("several installed kits are named '" + kitRef
                    + "' — address one by its id: "
                    + byName.stream().map(KitInstalledRecordDto::getId).toList());
        }
        throw new ToolException("no installed kit '" + kitRef + "' in project " + projectId
                + " — call kit_status to list them");
    }
}
