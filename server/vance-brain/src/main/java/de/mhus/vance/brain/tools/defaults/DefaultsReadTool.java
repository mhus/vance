package de.mhus.vance.brain.tools.defaults;

import static de.mhus.vance.brain.tools.defaults.DefaultsListTool.CLASSPATH_ROOT;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * Reads one bundled Vance configuration resource as text — the companion
 * to {@link DefaultsListTool}. See that class for the scope (classpath
 * layer only, no tenant overrides) and the creator-only label gate.
 *
 * <p>The path input is relative to {@code vance-defaults/}, exactly as
 * returned by {@code defaults_list}. Reading is restricted to that root:
 * a path with {@code ..} or an absolute form is refused rather than
 * reinterpreted, so the tool never escapes the bundled tree.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultsReadTool implements Tool {

    private static final int MAX_BYTES = 512 * 1024;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "path",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Path of the bundled resource to read, relative "
                                            + "to vance-defaults/ (as returned by defaults_list), "
                                            + "e.g. '_vance/recipes/creator.yaml'.")),
            "required", List.of("path"));

    private final ResourcePatternResolver resourcePatternResolver;

    @Override
    public String name() {
        return "defaults_read";
    }

    @Override
    public String description() {
        return "Read one bundled Vance configuration resource as text — the "
                + "canonical shipped version under vance-defaults/, not a "
                + "tenant override. Pass a path as returned by defaults_list "
                + "(relative to vance-defaults/, e.g. '_vance/recipes/creator.yaml'). "
                + "Returns the raw content; YAML, Markdown and Pebble are all "
                + "plain text. Use as a reference for structure when building "
                + "or verifying configuration, not as the effective config — "
                + "for that use recipe_describe, doc_read or manual_read.";
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
        String path = paramString(params, "path");
        if (path == null) {
            throw new ToolException("defaults_read requires a 'path' parameter");
        }
        String normalized = normalizePath(path);
        Resource resource = resourcePatternResolver.getResource("classpath:" + CLASSPATH_ROOT + normalized);
        if (!resource.exists() || !resource.isReadable()) {
            throw new ToolException("No bundled default at '" + normalized + "' (relative to " + CLASSPATH_ROOT
                    + "). Use defaults_list to see available paths.");
        }
        byte[] bytes;
        try (InputStream in = resource.getInputStream()) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        } catch (IOException e) {
            throw new ToolException("Failed to read bundled default '" + normalized + "': " + e.getMessage(), e);
        }
        boolean truncated = bytes.length > MAX_BYTES;
        String content =
                new String(truncated ? java.util.Arrays.copyOf(bytes, MAX_BYTES) : bytes, StandardCharsets.UTF_8);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", normalized);
        out.put("contentLength", bytes.length);
        out.put("truncated", truncated);
        if (truncated) {
            out.put("note", "Content truncated at " + MAX_BYTES + " bytes.");
        }
        out.put("content", content);
        return out;
    }

    static String normalizePath(String path) {
        String trimmed = path.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        // Refuse traversal — the tool must stay inside vance-defaults/.
        if (trimmed.contains("..") || trimmed.contains("\\") || trimmed.startsWith("vance-defaults/")) {
            throw new ToolException("Refused path '" + path + "': defaults_read only accepts a path "
                    + "relative to vance-defaults/ without '..' segments.");
        }
        if (trimmed.isEmpty()) {
            throw new ToolException("defaults_read refuses an empty path");
        }
        return trimmed;
    }

    private static @org.jspecify.annotations.Nullable String paramString(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
