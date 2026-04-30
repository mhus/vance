package de.mhus.vance.brain.tools.manual;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads one Markdown manual by name. Walks the recipe-configured
 * {@code params.manualPaths} in order; first folder where
 * {@code <folder>/<name>.md} resolves through the document cascade
 * wins. Pair with {@link ManualListTool} which surfaces the catalogue.
 *
 * <p>Path sanitisation: {@code /}, {@code ..} and {@code \} are
 * rejected so the LLM can't pull arbitrary files via name injection.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ManualReadTool implements Tool {

    private static final String MD_SUFFIX = ".md";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description",
                                    "Manual name without the .md suffix, "
                                            + "e.g. 'getting-started'.")),
            "required", List.of("name"));

    private final DocumentService documentService;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String name() {
        return "manual_read";
    }

    @Override
    public String description() {
        return "Read a specific manual by name. Use manual_list first to "
                + "see what's available. Pass the name without the .md "
                + "extension.";
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
            throw new ToolException("manual_read requires a tenant scope");
        }
        Object raw = params == null ? null : params.get("name");
        if (!(raw instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        if (name.contains("/") || name.contains("..") || name.contains("\\")) {
            throw new ToolException("Invalid manual name: " + name);
        }

        List<String> folders = ManualPaths.readFor(ctx, thinkProcessService);
        if (folders.isEmpty()) {
            throw new ToolException("No manualPaths configured in the recipe.");
        }

        for (String folder : folders) {
            String path = folder + name + MD_SUFFIX;
            Optional<LookupResult> hit = documentService.lookupCascade(
                    ctx.tenantId(), ctx.projectId(), path);
            if (hit.isPresent()) {
                LookupResult result = hit.get();
                String content = result.content() == null ? "" : result.content();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name", name);
                out.put("folder", folder);
                out.put("content", content);
                out.put("chars", content.length());
                out.put("source", result.source().name().toLowerCase());
                return out;
            }
        }
        throw new ToolException(
                "Manual not found: '" + name + "'. Use manual_list to "
                        + "see what's available in: " + folders);
    }
}
