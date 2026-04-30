package de.mhus.vance.brain.tools.kit;

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
 * Reports the active kit-manifest of a project, or {@code null} when
 * no kit is installed.
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

    @Override
    public String name() {
        return "kit_status";
    }

    @Override
    public String description() {
        return "Return the active kit's manifest (name, origin, files, settings, tools) "
                + "or null if no kit is installed in the project.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.tenantId() == null) {
            throw new ToolException("kit_status requires a tenant scope");
        }
        String projectId = KitToolSupport.requireProject(ctx,
                KitToolSupport.optionalString(params, "project"));
        KitManifestDto manifest = kitService.status(ctx.tenantId(), projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (manifest == null) {
            out.put("active", false);
            return out;
        }
        out.put("active", true);
        out.put("project", projectId);
        out.put("kit", Map.of(
                "name", manifest.getKit().getName(),
                "description", manifest.getKit().getDescription(),
                "version", manifest.getKit().getVersion()));
        out.put("origin", originMap(manifest));
        out.put("counts", Map.of(
                "documents", manifest.getDocuments().size(),
                "settings", manifest.getSettings().size(),
                "tools", manifest.getTools().size()));
        if (!manifest.getResolvedInherits().isEmpty()) {
            out.put("inherits", manifest.getResolvedInherits());
        }
        if (manifest.isHasEncryptedSecrets()) {
            out.put("hasEncryptedSecrets", true);
        }
        return out;
    }

    private static Map<String, Object> originMap(KitManifestDto manifest) {
        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("url", manifest.getOrigin().getUrl());
        if (manifest.getOrigin().getPath() != null) origin.put("path", manifest.getOrigin().getPath());
        if (manifest.getOrigin().getBranch() != null) origin.put("branch", manifest.getOrigin().getBranch());
        if (manifest.getOrigin().getCommit() != null) origin.put("commit", manifest.getOrigin().getCommit());
        if (manifest.getOrigin().getInstalledAt() != null) {
            origin.put("installedAt", manifest.getOrigin().getInstalledAt().toString());
        }
        if (manifest.getOrigin().getInstalledBy() != null) {
            origin.put("installedBy", manifest.getOrigin().getInstalledBy());
        }
        return origin;
    }
}
