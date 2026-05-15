package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Creates or overwrites a UTF-8 file on the foot host. Parent
 * directories are created as needed. Use this for fresh files or
 * complete rewrites — for targeted edits prefer
 * {@link ClientFileEditTool}.
 */
@Component
public class ClientFileWriteTool implements ClientTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Absolute or working-dir relative file path."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Full file content. Replaces any existing content.")),
            "required", List.of("path", "content"));

    @Override
    public String name() {
        return "client_file_write";
    }

    @Override
    public String description() {
        return "Create or overwrite a UTF-8 file on the USER'S OWN "
                + "MACHINE (the foot host's filesystem). Use this only "
                + "when the user explicitly asks to write to their "
                + "local disk — e.g. a code project they're editing "
                + "outside Vance, a lab notebook, downloads they "
                + "want to keep. The file lands wherever the user "
                + "browses with their own editor; Vance can read it "
                + "back via client_file_read but does not index or "
                + "search it. "
                + "NOT for: research notes the user wants to find "
                + "later inside Vance (use doc_create_text), or "
                + "scratch / scriptable data for project-side "
                + "processing (use workspace_write). "
                + "Parent directories are created as needed.";
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
    public java.util.Set<String> labels() {
        // "client-file" tells the history-tagging hook to encode the
        // returned "path" as a CLIENT_FILE: resource key — see
        // planning/process-history-search.md §5.1.
        return java.util.Set.of("write", "side-effect", "client-file");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object rawPath = params == null ? null : params.get("path");
        Object rawContent = params == null ? null : params.get("content");
        if (!(rawPath instanceof String path) || path.isBlank()) {
            throw new IllegalArgumentException("'path' is required");
        }
        if (!(rawContent instanceof String content)) {
            throw new IllegalArgumentException("'content' is required");
        }
        Path p = ClientFilePaths.resolve(path);
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("Write failed: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", p.toAbsolutePath().toString());
        out.put("chars", content.length());
        return out;
    }
}
