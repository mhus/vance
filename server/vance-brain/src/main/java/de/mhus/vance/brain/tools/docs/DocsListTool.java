package de.mhus.vance.brain.tools.docs;

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
 * Lists bundled Markdown how-tos under {@code classpath:docs/*.md}.
 * The LLM uses this when the user asks about Vance internals or when
 * it needs to operate an advanced feature it isn't sure about.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocsListTool implements Tool {

    static final String DOCS_PATTERN = "classpath:docs/*.md";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

    @Override
    public String name() {
        return "docs_list";
    }

    @Override
    public String description() {
        return "List bundled Markdown how-tos describing Vance internals — "
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
            throw new ToolException("Failed to list docs: " + e.getMessage(), e);
        }
    }
}
