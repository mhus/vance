package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitManifestDto;
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
 * Promotes an installed kit to this project's authoring manifest — the
 * "this project <em>is</em> the kit" decision of kits.md §4.3, exposed to
 * the creator worker.
 *
 * <p>Part of the kit-authoring tool family: deliberately wired into the
 * creator recipe only, kept out of the default chats' surfaces.
 */
@Component
@RequiredArgsConstructor
public class KitPromoteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "project",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Project to promote in. Defaults to the current project."),
                            "kit_id",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Id of the installed kit to promote — see kit_status.")),
            "required", List.of("kit_id"));

    private final KitService kitService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "kit_promote";
    }

    @Override
    public String description() {
        return "Mark this project as the source of one of its installed kits: writes the"
                + " authoring manifest (_vance/kits/manifest.yaml) plus the authored descriptor"
                + " beside it, from the install record — no re-clone, no reinstallation."
                + " A project can only ever be the source of one kit. After promoting, kit"
                + " updates keep the manifest in sync and kit_export can push the kit's"
                + " top layer back to its origin.";
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
        return Set.of("executive", "kit-authoring");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Kit authoring — make this project the source of an installed kit";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_promote requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProjectAuthorized(
                ctx,
                KitToolSupport.optionalString(params, "project"),
                permissionService,
                contextFactory,
                de.mhus.vance.shared.permission.Action.ADMIN);
        String kitId = KitToolSupport.requireString(params, "kit_id");
        KitManifestDto manifest = kitService.promoteToAuthoring(ctx.tenantId(), projectId, kitId, ctx.userId());
        return manifestSummary(manifest);
    }

    /** The map the other authoring tools report too — same shape, same reader. */
    static Map<String, Object> manifestSummary(KitManifestDto manifest) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kit", manifest.getKit().getName());
        if (manifest.getKit().getVersion() != null) {
            out.put("version", manifest.getKit().getVersion());
        }
        out.put("manifestPath", de.mhus.vance.brain.kit.KitRecordStore.MANIFEST_PATH);
        out.put("documents", manifest.getDocuments().size());
        out.put("settings", manifest.getSettings().size());
        if (!manifest.getInherits().isEmpty()) {
            out.put(
                    "inherits",
                    manifest.getInherits().stream().map(i -> i.getUrl()).toList());
        }
        if (manifest.getOrigin() != null && manifest.getOrigin().getUrl() != null) {
            out.put("originUrl", manifest.getOrigin().getUrl());
        }
        if (manifest.isHasEncryptedSecrets()) {
            out.put("hasEncryptedSecrets", true);
        }
        return out;
    }
}
