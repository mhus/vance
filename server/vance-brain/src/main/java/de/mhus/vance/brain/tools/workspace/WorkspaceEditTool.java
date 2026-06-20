package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Replaces exactly one occurrence of {@code oldText} with
 * {@code newText} inside a file in a project workspace RootDir.
 * Fails if the snippet isn't found or matches more than once — the
 * LLM is expected to add surrounding context until the match is
 * unique. Symmetric to {@code client_file_edit} on the foot side,
 * so the generic {@code file_edit} wrapper can dispatch to either
 * backend without semantic surprises.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceEditTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Relative path inside the RootDir."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional RootDir name. Defaults to the "
                                            + "current process's temp RootDir."),
                    "oldText", Map.of(
                            "type", "string",
                            "description", "Exact snippet to replace. Whitespace-sensitive."),
                    "newText", Map.of(
                            "type", "string",
                            "description", "Replacement text.")),
            "required", List.of("path", "oldText", "newText"));

    private final WorkspaceService workspace;
    private final WorkspaceProperties properties;

    @Override
    public String name() {
        return "work_file_edit";
    }

    @Override
    public String description() {
        return "Replace one occurrence of oldText with newText inside a "
                + "file in a project workspace RootDir. Fails if oldText "
                + "is not found or appears more than once — add surrounding "
                + "context until the match is unique. Preferred over "
                + "rewriting the whole file via work_file_write.";
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
        // "scratch" tag matches WorkspaceWriteTool — same history-tagging
        // hook encodes the returned path as SCRATCH: resource key.
        return java.util.Set.of("write", "side-effect", "scratch");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = stringOrThrow(params, "path");
        String oldText = stringOrThrow(params, "oldText");
        Object rawNew = params == null ? null : params.get("newText");
        if (!(rawNew instanceof String newText)) {
            throw new ToolException("'newText' is required");
        }
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        try {
            int cap = properties.getDefaultReadCharCap();
            WorkspaceService.ReadResult r =
                    workspace.read(ctx.tenantId(), ctx.projectId(), dirName, path, cap);
            if (r.truncated()) {
                throw new ToolException(
                        "File too large to edit safely (truncated at " + cap
                                + " chars). Use work_file_write to rewrite or split the change.");
            }
            String content = r.text();
            int first = content.indexOf(oldText);
            if (first < 0) {
                throw new ToolException("oldText not found in " + path);
            }
            int second = content.indexOf(oldText, first + oldText.length());
            if (second >= 0) {
                throw new ToolException(
                        "oldText appears multiple times — add context until unique");
            }
            String updated = content.substring(0, first) + newText
                    + content.substring(first + oldText.length());
            Path written = workspace.write(ctx.tenantId(), ctx.projectId(), dirName, path, updated);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("absolutePath", written.toString());
            out.put("replaced", 1);
            out.put("totalChars", updated.length());
            return out;
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
