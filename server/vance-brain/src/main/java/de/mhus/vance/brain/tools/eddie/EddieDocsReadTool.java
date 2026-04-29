package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads one Eddie hub-specific Markdown topic by name through the cascade
 * project → {@code _vance} → classpath
 * ({@code vance-defaults/eddie/docs/}).
 *
 * <p>Path sanitisation: {@code /} and {@code ..} are rejected so the
 * LLM can't pull arbitrary files off the classpath.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EddieDocsReadTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description",
                                    "Doc name without the .md suffix, "
                                            + "e.g. 'projects'.")),
            "required", List.of("name"));

    private final DocumentService documentService;

    @Override
    public String name() {
        return "eddie_docs_read";
    }

    @Override
    public String description() {
        return "Read a specific Eddie hub documentation topic by name. "
                + "Use eddie_docs_list first to see what's available. "
                + "Pass the name without the .md extension.";
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
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("eddie_docs_read requires a tenant scope");
        }
        Object raw = params == null ? null : params.get("name");
        if (!(raw instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        if (name.contains("/") || name.contains("..") || name.contains("\\")) {
            throw new ToolException("Invalid doc name: " + name);
        }
        String path = EddieDocsListTool.DOCS_PREFIX + name + ".md";
        Optional<LookupResult> hit = documentService.lookupCascade(
                ctx.tenantId(), ctx.projectId(), path);
        if (hit.isEmpty()) {
            throw new ToolException("Eddie doc not found: '" + name + "'. "
                    + "Use eddie_docs_list to see what's available.");
        }
        LookupResult result = hit.get();
        String content = result.content() == null ? "" : result.content();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("content", content);
        out.put("chars", content.length());
        out.put("source", result.source().name().toLowerCase());
        return out;
    }
}
