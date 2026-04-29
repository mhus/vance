package de.mhus.vance.brain.tools.docs;

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
 * Lists Markdown how-tos under the {@code docs/} path. Resolution goes
 * through {@link DocumentService#listByPrefixCascade(String, String, String)},
 * so the user's project and the tenant-wide {@code _vance} project can
 * shadow / extend the bundled defaults shipped under
 * {@code classpath:vance-defaults/docs/*.md}. Inner sources win on the
 * same path; one entry per name.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocsListTool implements Tool {

    /** Folder prefix used by the cascade — applies to project, _vance, and the classpath. */
    static final String DOCS_PREFIX = "docs/";

    private static final String MD_SUFFIX = ".md";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final DocumentService documentService;

    @Override
    public String name() {
        return "docs_list";
    }

    @Override
    public String description() {
        return "List Markdown how-tos describing Vance internals — "
                + "memory, RAG, tools, sub-processes, etc. Read one with "
                + "docs_read(name). Consult these whenever the user asks "
                + "about Vance itself or you need to operate a feature you "
                + "are unsure about.";
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
            throw new ToolException("docs_list requires a tenant scope");
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
