package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deletes a single file on the foot host. Directories are refused — a
 * recursive delete is a different, far more destructive operation and
 * has no business hiding behind a tool whose name says "file".
 *
 * <p>Gated by the sandbox's dedicated {@code delete} rule domain rather
 * than the shared path rules, so an allow the user wrote to let the
 * agent read a tree does not also let it empty that tree (see
 * {@code ClientSecurityService} and {@code PermissionDomain#DELETE}).
 * Deferred on purpose: the reachable surface is the {@code file_delete}
 * wrapper.
 */
@Component
public class ClientFileDeleteTool implements ClientTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Absolute or working-dir relative file path.")),
            "required", List.of("path"));

    @Override
    public String name() {
        return "client_file_delete";
    }

    @Override
    public String description() {
        return "Delete a single file from the USER'S OWN MACHINE (the foot "
                + "host's filesystem). Safe to call on a path that doesn't "
                + "exist — returns deleted=false. Directories are refused. "
                + "The user's sandbox policy must permit deletion of that "
                + "path specifically; permission to read or write it is not "
                + "enough, so expect an interactive confirmation. "
                + "NOT for project documents (use doc_delete) or brain "
                + "workspace files (use work_file_delete).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Delete a file on the user's machine — destructive, needs approval";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "side-effect", "client-file");
    }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Requires CLIENT target — Foot must be connected. Denied = the user's "
                + "delete rules don't cover this path (read/write allows don't count).";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem", "client");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object rawPath = params == null ? null : params.get("path");
        if (!(rawPath instanceof String path) || path.isBlank()) {
            throw new IllegalArgumentException("'path' is required");
        }
        Path p = ClientFilePaths.resolve(path);
        if (Files.isDirectory(p)) {
            throw new IllegalArgumentException(
                    "'" + p.toAbsolutePath() + "' is a directory — this tool deletes files only");
        }
        boolean deleted;
        try {
            deleted = Files.deleteIfExists(p);
        } catch (Exception e) {
            throw new RuntimeException(ClientFilePaths.describeFailure(p, e, "Delete"), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", p.toAbsolutePath().toString());
        out.put("deleted", deleted);
        return out;
    }
}
