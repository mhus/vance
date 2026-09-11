package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitAuthoringValidationDto;
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
 * Validates the project's kit-source setup — the pre-flight check of the
 * authoring flow: manifest parses, descriptor agrees, every claimed
 * artefact exists, the encrypted-secrets flag is honest. Run before
 * {@code kit_export} and after any hand-edit of the manifest.
 *
 * <p>Part of the kit-authoring tool family: deliberately wired into the
 * creator recipe only, kept out of the default chats' surfaces.
 */
@Component
@RequiredArgsConstructor
public class KitSourceValidateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "project",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Project to validate. Defaults to the current project.")),
            "required", List.of());

    private final KitService kitService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "kit_source_validate";
    }

    @Override
    public String description() {
        return "Check this project's kit-source setup without writing anything: does the"
                + " authoring manifest parse, does every listed document and setting exist,"
                + " is the encrypted-secrets flag honest, does the descriptor beside the"
                + " manifest agree with it. Reports errors (export would not do what the"
                + " manifest says) and warnings (works, but deserves a look) — fix them"
                + " before calling kit_export.";
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
        return Set.of("read-only", "kit-authoring");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Kit authoring — check manifest and artefacts before exporting a kit";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_source_validate requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProjectAuthorized(
                ctx,
                KitToolSupport.optionalString(params, "project"),
                permissionService,
                contextFactory,
                de.mhus.vance.shared.permission.Action.READ);
        KitAuthoringValidationDto result = kitService.validateAuthoring(ctx.tenantId(), projectId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", result.isValid());
        if (result.getKitName() != null) out.put("kit", result.getKitName());
        if (result.getKitVersion() != null) out.put("version", result.getKitVersion());
        out.put("documents", result.getDocuments());
        out.put("settings", result.getSettings());
        if (result.getOriginUrl() != null) out.put("originUrl", result.getOriginUrl());
        if (result.isHasEncryptedSecrets()) {
            out.put("hasEncryptedSecrets", true);
            out.put("vaultPasswordRequired", true);
        }
        if (!result.getErrors().isEmpty()) out.put("errors", result.getErrors());
        if (!result.getWarnings().isEmpty()) out.put("warnings", result.getWarnings());
        return out;
    }
}
