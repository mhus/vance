package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Replaces exactly one occurrence of {@code oldText} with
 * {@code newText} inside a file on the foot host. Fails if the
 * snippet isn't found or matches more than once — the LLM is
 * expected to add surrounding context until the match is unique.
 *
 * <p>Same contract as the prototype's {@code editFile}: targeted
 * edits stay safe (no accidental multi-replace) and cheap (no need
 * to re-write the whole file).
 */
@Component
public class ClientFileEditTool implements ClientTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Absolute or working-dir relative file path."),
                    "oldText", Map.of(
                            "type", "string",
                            "description", "Exact snippet to replace. Whitespace-sensitive."),
                    "newText", Map.of(
                            "type", "string",
                            "description", "Replacement text.")),
            "required", List.of("path", "oldText", "newText"));

    @Override
    public String name() {
        return "client_file_edit";
    }

    @Override
    public String description() {
        return "Replace one occurrence of oldText with newText inside a file "
                + "on the foot host. Fails if oldText is not found or appears "
                + "more than once — add surrounding context until the match "
                + "is unique. Preferred over rewriting the whole file.";
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
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object rawPath = params == null ? null : params.get("path");
        Object rawOld = params == null ? null : params.get("oldText");
        Object rawNew = params == null ? null : params.get("newText");
        if (!(rawPath instanceof String path) || path.isBlank()) {
            throw new IllegalArgumentException("'path' is required");
        }
        if (!(rawOld instanceof String oldText) || oldText.isEmpty()) {
            throw new IllegalArgumentException("'oldText' is required");
        }
        if (!(rawNew instanceof String newText)) {
            throw new IllegalArgumentException("'newText' is required");
        }
        Path p = ClientFilePaths.resolve(path);
        try {
            String content = Files.readString(p, StandardCharsets.UTF_8);
            int first = content.indexOf(oldText);
            if (first < 0) {
                throw new IllegalArgumentException("oldText not found in " + p.toAbsolutePath());
            }
            int second = content.indexOf(oldText, first + oldText.length());
            if (second >= 0) {
                throw new IllegalArgumentException(
                        "oldText appears multiple times — add context until unique");
            }
            String updated = content.substring(0, first) + newText
                    + content.substring(first + oldText.length());
            Files.writeString(p, updated, StandardCharsets.UTF_8);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", p.toAbsolutePath().toString());
            out.put("replaced", 1);
            out.put("totalChars", updated.length());
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Edit failed: " + e.getMessage(), e);
        }
    }
}
