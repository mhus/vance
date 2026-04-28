package de.mhus.vance.brain.tools.vance;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Lists Vance hub-specific documentation under
 * {@code classpath:vance/docs/*.md}. Separate from the general
 * Brain docs ({@code docs_list}) so Vance can have her own onboarding
 * material without cluttering worker-engine docs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VanceDocsListTool implements Tool {

    static final String DOCS_PATTERN = "classpath:vance/docs/*.md";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

    @Override
    public String name() {
        return "vance_docs_list";
    }

    @Override
    public String description() {
        return "List Vance hub-specific documentation topics — how to "
                + "work with projects, recipes, hub conventions, "
                + "easter eggs. Read one with vance_docs_read(name). "
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
        try {
            Resource[] resources = resolver.getResources(DOCS_PATTERN);
            List<String> names = new ArrayList<>();
            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename != null && filename.endsWith(".md")) {
                    names.add(filename.substring(0, filename.length() - ".md".length()));
                }
            }
            names.sort(String::compareTo);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("docs", names);
            out.put("count", names.size());
            return out;
        } catch (IOException e) {
            throw new ToolException("Failed to list Vance docs: " + e.getMessage(), e);
        }
    }
}
