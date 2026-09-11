package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitAuthoringRequestDto;
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
 * Turns a project into a kit source <em>from scratch</em> — for the kit
 * that was never installed anywhere. The authoring-manifest counterpart of
 * {@link KitPromoteTool}: promote grows a manifest out of an install
 * record, this one declares a manifest over project content the author
 * wrote by hand (kits.md §4.3, third path).
 *
 * <p>Part of the kit-authoring tool family: deliberately wired into the
 * creator recipe only, kept out of the default chats' surfaces.
 */
@Component
@RequiredArgsConstructor
public class KitSourceCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "project",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Project to become the kit source. Defaults to the current project."));
        properties.put(
                "name",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Logical kit name — the identity installs and inherits refer to."));
        properties.put("description", Map.of("type", "string", "description", "One-sentence what the kit provides."));
        properties.put(
                "version",
                Map.of("type", "string", "description", "Version metadata (display only — identity is the origin)."));
        properties.put(
                "origin_url",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Git remote the kit lives at — kit_export pushes here by"
                                + " default. Must be remote (https://, git@, ssh://)."));
        properties.put(
                "origin_branch",
                Map.of("type", "string", "description", "Branch at the origin. Defaults to the remote's default."));
        properties.put(
                "origin_path",
                Map.of("type", "string", "description", "Sub-path inside the origin repo. Defaults to repo root."));
        properties.put(
                "documents",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Project document paths the kit ships. Every entry must exist."));
        properties.put(
                "settings",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Project-scoped setting keys the kit ships. Every entry must exist."));
        properties.put(
                "inherits",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Source urls of kits this one inherits from (git url or"
                                + " project:<name>), one entry per inherits line."));
        SCHEMA = Map.of(
                "type", "object", "properties", properties, "required", List.of("name", "description", "origin_url"));
    }

    private final KitService kitService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "kit_source_create";
    }

    @Override
    public String description() {
        return "Make this project the source of a new kit, from scratch: writes the authoring"
                + " manifest (_vance/kits/manifest.yaml) declaring the named documents and"
                + " settings as the kit's top layer, plus a starter descriptor"
                + " (_vance/kits/kit.yaml). Use when developing a kit this project authored"
                + " itself — when the kit was installed from a repo instead, promote it with"
                + " kit_promote. Every listed artefact must exist; the encrypted-secrets flag"
                + " is computed from the settings' actual types.";
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
        return "Kit authoring — declare this project's content as a new kit";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_source_create requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProjectAuthorized(
                ctx,
                KitToolSupport.optionalString(params, "project"),
                permissionService,
                contextFactory,
                de.mhus.vance.shared.permission.Action.ADMIN);
        KitAuthoringRequestDto request = KitAuthoringRequestDto.builder()
                .projectId(projectId)
                .name(KitToolSupport.requireString(params, "name"))
                .description(KitToolSupport.requireString(params, "description"))
                .version(KitToolSupport.optionalString(params, "version"))
                .originUrl(KitToolSupport.requireRemoteUrlIfPresent(
                        KitToolSupport.requireString(params, "origin_url"), "kit_source_create"))
                .originBranch(KitToolSupport.optionalString(params, "origin_branch"))
                .originPath(KitToolSupport.optionalString(params, "origin_path"))
                .documents(KitToolSupport.optionalStringList(params, "documents"))
                .settings(KitToolSupport.optionalStringList(params, "settings"))
                .inherits(KitToolSupport.optionalStringList(params, "inherits"))
                .build();
        KitManifestDto manifest = kitService.createAuthoringManifest(ctx.tenantId(), request, ctx.userId());
        return KitPromoteTool.manifestSummary(manifest);
    }
}
