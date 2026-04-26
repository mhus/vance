package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Lists the entries of a directory on the foot host (non-recursive,
 * sorted). Directories are flagged with a trailing {@code "/"} so
 * the LLM can tell them apart without a follow-up call.
 */
@Component
public class ClientFileListTool implements ClientTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Directory path. Default: working directory.")),
            "required", List.of());

    @Override
    public String name() {
        return "client_file_list";
    }

    @Override
    public String description() {
        return "List the entries of a directory on the user's machine "
                + "(non-recursive). Directories are returned with a trailing "
                + "'/' suffix.";
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
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object rawPath = params == null ? null : params.get("path");
        Path p = (rawPath instanceof String s && !s.isBlank()) ? ClientFilePaths.resolve(s) : Path.of(".");
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException(
                    "Not a directory: " + p.toAbsolutePath());
        }
        List<String> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(p)) {
            stream.sorted()
                    .forEach(e -> entries.add(
                            e.getFileName().toString() + (Files.isDirectory(e) ? "/" : "")));
        } catch (Exception e) {
            throw new RuntimeException("List failed: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", p.toAbsolutePath().toString());
        out.put("entries", entries);
        out.put("count", entries.size());
        return out;
    }
}
