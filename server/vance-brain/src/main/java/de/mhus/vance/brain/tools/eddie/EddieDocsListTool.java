package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lists Eddie hub-specific documentation under the {@code eddie/docs/}
 * cascade — bundled defaults under
 * {@code classpath:vance-defaults/eddie/docs/*.md} can be shadowed or
 * extended by the user's project and the tenant-wide {@code _vance}
 * project. Separate from the general docs ({@code docs_list}) so Eddie
 * can carry her own onboarding material without cluttering worker-engine
 * docs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EddieDocsListTool implements Tool {

    /** Folder prefix used by the cascade — applies to project, _vance, and the classpath. */
    static final String DOCS_PREFIX = "eddie/docs/";

    private static final String MD_SUFFIX = ".md";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final DocumentService documentService;

    @Override
    public String name() {
        return "eddie_docs_list";
    }

    @Override
    public String description() {
        return "List Eddie hub-specific documentation topics — how to "
                + "work with projects, recipes, hub conventions, "
                + "easter eggs. Read one with eddie_docs_read(name). "
                + "Use this when the user asks about Vance herself or "
                + "how the hub works.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("eddie_docs_list requires a tenant scope");
        }
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                ctx.tenantId(), ctx.projectId(), DOCS_PREFIX);
        List<String> names = new ArrayList<>();
        for (String path : hits.keySet()) {
            if (!path.endsWith(MD_SUFFIX)) continue;
            String filename = path.substring(DOCS_PREFIX.length());
            names.add(filename.substring(0, filename.length() - MD_SUFFIX.length()));
        }
        names.sort(String::compareTo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docs", names);
        out.put("count", names.size());
        return out;
    }
}
