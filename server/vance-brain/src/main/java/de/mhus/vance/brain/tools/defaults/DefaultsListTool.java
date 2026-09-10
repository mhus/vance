package de.mhus.vance.brain.tools.defaults;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Lists the <b>bundled</b> Vance configuration — the classpath resources
 * under {@code vance-defaults/} (recipes, model catalog docs, manuals,
 * prompts, templates, wizards, setting forms, skills). The counterpart
 * {@link DefaultsReadTool} reads one of these paths as text.
 *
 * <p>These tools return <b>only the classpath layer</b> — deliberately
 * not the document cascade. The name "defaults" promises the canonical
 * Vance configuration as shipped, which is exactly what an agent wants
 * as a reference: how a recipe is structured, what a provider sidecar
 * looks like, the shape of a manual. Tenant overrides live in the
 * database and are visible through the effective-config surfaces
 * ({@code recipe_describe}, {@code doc_read}, {@code manual_read}).
 * Keeping the two separate avoids the confusion of a merged view where
 * the agent cannot tell bundled from overridden.
 *
 * <p>Creator-only by label: the {@code @defaults} selector is promoted
 * only in the creator recipe. No secrets are involved — bundled
 * resources carry metadata, prompts and schemas, never API keys — so
 * the dispatcher's EXECUTE check on the caller's own process scope is
 * the only gate.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultsListTool implements Tool {

    /** Classpath root of the bundled defaults. Public for the read twin. */
    static final String CLASSPATH_ROOT = "vance-defaults/";

    private static final String CLASSPATH_GLOB = "classpath*:" + CLASSPATH_ROOT + "**/*";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "path",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Optional path prefix relative to vance-defaults/ "
                                            + "(e.g. '_vance/recipes/', '_vance/model/openai/'). "
                                            + "Empty or omitted lists every bundled resource.")),
            "required", List.of());

    private final ResourcePatternResolver resourcePatternResolver;

    @Override
    public String name() {
        return "defaults_list";
    }

    @Override
    public String description() {
        return "List the bundled Vance configuration (classpath resources under "
                + "vance-defaults/) — recipes, model catalog docs, manuals, prompts, "
                + "templates, wizards, setting forms, skills. Returns the canonical "
                + "shipped defaults only, not tenant overrides. Pass a path prefix "
                + "(e.g. '_vance/recipes/', '_vance/model/openai/') to narrow the "
                + "listing; empty lists everything. Each entry's path is relative to "
                + "vance-defaults/ and is the input for defaults_read. Use as a "
                + "reference for how a recipe, provider sidecar or manual is structured.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("defaults");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String prefix = normalizePrefix(paramString(params, "path"));
        Resource[] resources;
        try {
            resources = resourcePatternResolver.getResources(CLASSPATH_GLOB);
        } catch (IOException e) {
            throw new ToolException("Failed to scan bundled defaults: " + e.getMessage(), e);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Resource resource : resources) {
            String rel = relativePath(resource);
            if (rel == null) {
                continue;
            }
            if (!rel.startsWith(prefix)) {
                continue;
            }
            // Skip directory markers — only files carry content.
            if (resource.isReadable() && rel.endsWith("/")) {
                continue;
            }
            long size;
            try {
                size = resource.contentLength();
            } catch (IOException ignored) {
                size = -1;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", rel);
            entry.put("size", size);
            entries.add(entry);
        }
        entries.sort(Comparator.comparing(m -> String.valueOf(m.get("path"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", CLASSPATH_ROOT);
        out.put("prefix", prefix);
        out.put("count", entries.size());
        out.put("entries", entries);
        return out;
    }

    static String normalizePrefix(@org.jspecify.annotations.Nullable String prefix) {
        if (prefix == null) {
            return "";
        }
        String trimmed = prefix.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        // Tolerate a leading slash: callers may type '/_vance/recipes/'.
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        // Treat a bare folder name as a folder prefix (append '/').
        if (!trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        return trimmed;
    }

    private @org.jspecify.annotations.Nullable String relativePath(Resource resource) {
        String uri = resource.toString();
        int idx = uri.indexOf(CLASSPATH_ROOT);
        if (idx < 0) {
            return null;
        }
        String rel = uri.substring(idx + CLASSPATH_ROOT.length());
        int closing = rel.indexOf(']');
        if (closing >= 0) {
            rel = rel.substring(0, closing);
        }
        return rel.replace('\\', '/');
    }

    private static @org.jspecify.annotations.Nullable String paramString(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
