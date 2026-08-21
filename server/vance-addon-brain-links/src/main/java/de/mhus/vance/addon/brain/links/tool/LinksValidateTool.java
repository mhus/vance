package de.mhus.vance.addon.brain.links.tool;

import de.mhus.vance.addon.brain.links.LinksValidationService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.kind.validate.KindValidationResult;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent self-check for a links manifest.
 *
 * <p>Reads the raw YAML and reports what the lenient load path drops in
 * silence — an entry without a usable URL, a duplicate URL, a field of the
 * wrong type. That silence is the reason this tool exists: writing the manifest
 * with the generic document tools is allowed, and a bad URL there produces no
 * complaint, just a missing card.
 */
@Component
@Slf4j
public class LinksValidateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The link-list folder to check (POST-write "
                                + "self-check). Give exactly one of folder / content."));
                put("content", Map.of("type", "string",
                        "description", "Manifest text you are about to write (PRE-write "
                                + "self-check). Give exactly one of folder / content."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of());

    private final EddieContext eddieContext;
    private final LinksValidationService validationService;

    public LinksValidateTool(EddieContext eddieContext,
                            LinksValidationService validationService) {
        this.eddieContext = eddieContext;
        this.validationService = validationService;
    }

    @Override public String name() { return "links_validate"; }

    @Override
    public String description() {
        return "Statically validate a link list: reports entries that would be silently "
                + "dropped (missing or unusable url, wrong types), duplicate URLs that make "
                + "an entry unreachable, and a wrong `$meta.app`. Read-only, advisory. "
                + "Returns { target, ok, errors, warnings, findings[] }. Run it after "
                + "editing `_app.yaml` by hand — the typed `links_entry_*` tools cannot "
                + "produce these faults.";
    }

    @Override public boolean primary() { return false; }

    @Override public boolean contributesPrak() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read-only", "document", "links");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = LinksToolSupport.paramString(params, "folder");
        String content = LinksToolSupport.paramString(params, "content");
        LinksValidationService.requireExactlyOne(folder, content);

        KindValidationResult result;
        if (content != null) {
            result = validationService.validateContent(content);
        } else {
            ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
            result = validationService.validateFolder(
                    ctx.tenantId(), project.getName(), folder);
        }
        log.info("LinksValidateTool target='{}' ok={} findings={}",
                result.target(), result.ok(), result.findings().size());
        return result.toMap();
    }
}
