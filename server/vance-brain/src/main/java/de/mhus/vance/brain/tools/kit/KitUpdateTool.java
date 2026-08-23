package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Re-runs installed kits against their origin.
 *
 * <p>Without arguments it updates <b>every</b> kit in the project — the
 * everyday case, since a project may carry several. A single kit can be
 * addressed by {@code kit} (its record id or name).
 *
 * <p>Passing {@code url} addresses a kit by its coordinates instead.
 * That is not a way to move an installed kit to a new source: identity
 * <i>is</i> {@code (url, path)}, so a new url means a new kit with its
 * own record. Moving a kit means uninstalling and installing it.
 *
 * <p>{@code prune=true} additionally deletes artefacts the kit no longer
 * ships, except those another installed kit also owns.
 */
@Component
@RequiredArgsConstructor
public class KitUpdateTool implements Tool {

    private final KitService kitService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "kit_update";
    }

    @Override
    public String description() {
        return "Re-fetch installed kits and refresh what they contribute. With no "
                + "arguments updates every installed kit; pass `kit` to update one. "
                + "Passing url/path addresses a kit by its source coordinates — note "
                + "that a different url is a different kit, not a relocation of an "
                + "existing one. `prune=true` removes artefacts the kit no longer ships.";
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
                "Delete artefacts the kit tracked before but no longer ships. Default false "
                        + "(they only drop out of the record). Never removes artefacts another "
                        + "installed kit also owns."));
        properties.put("kit", Map.of(
                "type", "string",
                "description",
                "Record id or name of a single installed kit to update. "
                        + "Omit to update every installed kit."));
        // Naming the source explicitly addresses a kit by its coordinates,
        // which is a different thing from picking an installed one.
        // url is optional — without it the installed records supply the source.
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of());
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("executive");
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
            throw new ToolException("kit_update requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProjectAuthorized(ctx,
                KitToolSupport.optionalString(params, "project"),
                permissionService, contextFactory, de.mhus.vance.shared.permission.Action.ADMIN);
        String token = KitToolSupport.optionalString(params, "token");
        String vaultPassword = KitToolSupport.optionalString(params, "vault_password");
        boolean prune = KitToolSupport.optionalBoolean(params, "prune");
        String url = KitToolSupport.optionalString(params, "url");
        String kit = KitToolSupport.optionalString(params, "kit");

        // An explicit url does NOT re-point an installed kit: identity is
        // (url, path), so a different url is a different kit and gets its
        // own record. This path exists for installing/updating a kit whose
        // coordinates the caller states outright.
        if (url != null) {
            KitImportRequestDto request = KitImportRequestDto.builder()
                    .projectId(projectId)
                    .source(KitInheritDto.builder()
                            .url(url)
                            .path(KitToolSupport.optionalString(params, "path"))
                            .branch(KitToolSupport.optionalString(params, "branch"))
                            .commit(KitToolSupport.optionalString(params, "commit"))
                            .build())
                    .token(token)
                    .vaultPassword(vaultPassword)
                    .mode(KitImportMode.UPDATE)
                    .prune(prune)
                    .build();
            return KitToolSupport.resultToMap(
                    kitService.importKit(ctx.tenantId(), request, ctx.userId(),
                            SettingWriteOrigin.AGENT));
        }

        if (kit != null) {
            return KitToolSupport.resultToMap(kitService.updateInstalled(
                    ctx.tenantId(), projectId, resolveKitId(ctx.tenantId(), projectId, kit),
                    prune, token, vaultPassword, ctx.userId(), SettingWriteOrigin.AGENT));
        }

        List<KitOperationResultDto> results = kitService.updateAllInstalled(
                ctx.tenantId(), projectId, prune, token, vaultPassword,
                ctx.userId(), SettingWriteOrigin.AGENT);
        if (results.isEmpty()) {
            throw new ToolException("no kits are installed in project " + projectId
                    + " — use kit_install first");
        }
        List<Map<String, Object>> mapped = new java.util.ArrayList<>(results.size());
        for (KitOperationResultDto r : results) mapped.add(KitToolSupport.resultToMap(r));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projectId);
        out.put("updated", mapped);
        return out;
    }

    /**
     * Accept either the record id or the kit's display name — the model
     * sees both in {@code kit_status} and should not have to know which
     * one is the key.
     */
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
