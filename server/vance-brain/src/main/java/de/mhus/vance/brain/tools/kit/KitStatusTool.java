package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitArtefactsDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists the kits installed in a project, plus the authoring manifest
 * when the project happens to be a kit source itself.
 */
@Component
@RequiredArgsConstructor
public class KitStatusTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "project", Map.of(
                            "type", "string",
                            "description",
                            "Project to inspect. Defaults to the current project.")),
            "required", List.of());

    private final KitService kitService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "kit_status";
    }

    @Override
    public String description() {
        return "List the kits installed in the project — id, name, version, origin and "
                + "artefact counts each — plus whether this project is itself a kit source.";
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
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_status requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProjectAuthorized(ctx,
                KitToolSupport.optionalString(params, "project"),
                permissionService, contextFactory, de.mhus.vance.shared.permission.Action.READ);
        List<KitInstalledRecordDto> installed = kitService.status(ctx.tenantId(), projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projectId);

        List<Map<String, Object>> kits = new ArrayList<>();
        for (KitInstalledRecordDto record : installed) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", record.getId());
            entry.put("name", record.getKit().getName());
            entry.put("description", record.getKit().getDescription());
            if (record.getKit().getVersion() != null) {
                entry.put("version", record.getKit().getVersion());
            }
            entry.put("origin", originMap(record.getOrigin()));
            KitArtefactsDto artefacts = record.getArtefacts();
            entry.put("counts", Map.of(
                    "documents", artefacts == null ? 0 : artefacts.getDocuments().size(),
                    "settings", artefacts == null ? 0 : artefacts.getSettings().size()));
            if (record.isHasEncryptedSecrets()) {
                entry.put("hasEncryptedSecrets", true);
            }
            kits.add(entry);
        }
        out.put("installed", kits);

        // A project is only a kit *source* when someone said so — reporting
        // it here keeps the two concepts visibly apart for the model.
        KitManifestDto manifest = kitService.authoringManifest(ctx.tenantId(), projectId);
        out.put("isKitSource", manifest != null);
        if (manifest != null) {
            out.put("kitSource", Map.of(
                    "name", manifest.getKit().getName(),
                    "origin", originMap(manifest.getOrigin())));
        }
        return out;
    }

    private static Map<String, Object> originMap(KitOriginDto source) {
        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("url", source.getUrl());
        if (source.getPath() != null) origin.put("path", source.getPath());
        if (source.getBranch() != null) origin.put("branch", source.getBranch());
        if (source.getCommit() != null) origin.put("commit", source.getCommit());
        if (source.getInstalledAt() != null) {
            origin.put("installedAt", source.getInstalledAt().toString());
        }
        if (source.getInstalledBy() != null) {
            origin.put("installedBy", source.getInstalledBy());
        }
        return origin;
    }
}
