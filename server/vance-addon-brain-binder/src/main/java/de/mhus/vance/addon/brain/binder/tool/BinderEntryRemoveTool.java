package de.mhus.vance.addon.brain.binder.tool;

import de.mhus.vance.addon.brain.binder.BinderManifestOps;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Detach a document from a binder (the target document is untouched). */
@Component
@Slf4j
public class BinderEntryRemoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The binder folder (holding _app.yaml)."));
                put("ref", Map.of("type", "string",
                        "description", "The entry to remove, a vance: ref or project path."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "ref"));

    private final EddieContext eddieContext;
    private final BinderManifestOps manifestOps;

    public BinderEntryRemoveTool(EddieContext eddieContext, BinderManifestOps manifestOps) {
        this.eddieContext = eddieContext;
        this.manifestOps = manifestOps;
    }

    @Override public String name() { return "binder_entry_remove"; }

    @Override
    public String description() {
        return "Detach a document from a binder. Only the reference is removed — the "
                + "target document itself is not deleted.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "binder");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = BinderToolSupport.paramString(params, "folder");
        String ref = BinderToolSupport.paramString(params, "ref");
        if (folder == null) throw new ToolException("folder is required");
        if (ref == null) throw new ToolException("ref is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        manifestOps.removeEntry(ctx.tenantId(), project.getName(), folder, ref, ctx.userId());

        log.info("BinderEntryRemoveTool folder='{}' ref='{}'", folder, ref);
        return Map.of("folder", folder, "ref", ref, "removed", true);
    }
}
