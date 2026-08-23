package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists one directory level inside a project workspace RootDir — the WORK
 * half of the {@code file_list} wrapper.
 *
 * <p>Deliberately <b>not</b> recursive, and deliberately including
 * directories (marked with a trailing {@code /}): the CLIENT half
 * ({@code client_file_list}) has always behaved that way, and one wrapper
 * name that returns a flat directory listing on one target and every file in
 * the tree on the other is a trap. Recursive listing is what
 * {@code file_find} is for.
 *
 * <p>When {@code dirName} is omitted, the per-process temp RootDir is used;
 * when {@code path} is omitted, the RootDir root is listed.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional RootDir name. Defaults to the "
                                            + "current process's temp RootDir."),
                    "path", Map.of(
                            "type", "string",
                            "description",
                                    "Subdirectory inside the RootDir to list. "
                                            + "Default: the RootDir root.")),
            "required", List.of());

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "work_file_list";
    }

    @Override
    public String description() {
        return "List one directory level in a project workspace RootDir. "
                + "Returns entry names, directories marked with a trailing '/'. "
                + "Use file_find for a recursive listing.";
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
        return "Explicit WORK variant of file_list — targets the brain workspace regardless of the work target. Prefer file_list.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("read-only", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        String subPath = stringOrNull(params, "path");
        WorkspaceSubPath.requirePresent(
                workspace, ctx, dirName, subPath, /*requireDirectory*/ true);
        String prefix = WorkspaceSubPath.prefix(subPath);
        List<String> all;
        try {
            all = workspace.list(ctx.tenantId(), ctx.projectId(), dirName);
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
        // WorkspaceService.list is recursive and file-only; fold it back to a
        // single level. The first segment below the prefix is the entry name,
        // and anything with a further segment is a directory.
        Set<String> entries = new TreeSet<>();
        for (String relPath : all) {
            String under = WorkspaceSubPath.under(relPath, prefix);
            if (under == null) continue;
            int slash = under.indexOf('/');
            entries.add(slash < 0 ? under : under.substring(0, slash) + "/");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", dirName);
        out.put("path", subPath == null ? "." : subPath);
        out.put("entries", List.copyOf(entries));
        out.put("count", entries.size());
        return out;
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
