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

/** Anchor a document into a binder. */
@Component
@Slf4j
public class BinderEntryAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The binder folder (holding _app.yaml)."));
                put("ref", Map.of("type", "string",
                        "description", "Target document, a vance: ref or project path "
                                + "(e.g. 'vance:/reports/q1.sheet.yaml' or 'reports/q1.sheet.yaml')."));
                put("section", Map.of("type", "string",
                        "description", "Optional grouping label in the sidebar."));
                put("title", Map.of("type", "string",
                        "description", "Optional display-title override."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "ref"));

    private final EddieContext eddieContext;
    private final BinderManifestOps manifestOps;

    public BinderEntryAddTool(EddieContext eddieContext, BinderManifestOps manifestOps) {
        this.eddieContext = eddieContext;
        this.manifestOps = manifestOps;
    }

    @Override public String name() { return "binder_entry_add"; }

    @Override
    public String description() {
        return "Anchor a document into a binder as an ordered entry. Idempotent — "
                + "adding the same document twice is a no-op. The binder only references "
                + "the document; to change its content, edit the target directly.";
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
        String section = BinderToolSupport.paramString(params, "section");
        String title = BinderToolSupport.paramString(params, "title");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        manifestOps.addEntry(ctx.tenantId(), project.getName(), folder,
                ref, section, title, ctx.userId());

        log.info("BinderEntryAddTool folder='{}' ref='{}'", folder, ref);
        return Map.of("folder", folder, "ref", ref, "added", true);
    }
}
