package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * List documents that live directly inside one folder (non-recursive
 * by default). The Vance equivalent of `ls foo/`.
 */
@Component
@RequiredArgsConstructor
public class DocListInFolderTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Optional project name. Defaults to the active project."),
                    "folder", Map.of("type", "string",
                            "description", "Folder path inside the project (e.g. 'notes/2024'). "
                                    + "Empty string = project root."),
                    "recursive", Map.of("type", "boolean",
                            "description", "Include documents in subfolders too. Default: false.")),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "doc_list_in_folder"; }
    @Override public String description() {
        return "List documents directly inside `folder` (non-recursive by default). Set "
                + "`recursive=true` to include subfolders. Trashed documents are filtered out.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("folders", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        String folder = KindToolSupport.paramString(params, "folder");
        boolean recursive = Boolean.TRUE.equals(KindToolSupport.paramBoolean(params, "recursive"));

        String prefix = folder == null || folder.isBlank()
                ? ""
                : (folder.endsWith("/") ? folder : folder + "/");

        List<DocumentDocument> all = support.documentService()
                .listByProject(ctx.tenantId(), project.getName());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DocumentDocument d : all) {
            if (DocumentService.isTrash(d.getPath())) continue;
            if (!d.getPath().startsWith(prefix)) continue;
            String tail = d.getPath().substring(prefix.length());
            if (!recursive && tail.contains("/")) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", d.getId());
            r.put("path", d.getPath());
            r.put("name", d.getName());
            if (d.getTitle() != null) r.put("title", d.getTitle());
            if (d.getMimeType() != null) r.put("mimeType", d.getMimeType());
            if (d.getKind() != null) r.put("kind", d.getKind());
            r.put("size", d.getSize());
            if (d.getTags() != null && !d.getTags().isEmpty()) r.put("tags", d.getTags());
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("folder", prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1));
        out.put("recursive", recursive);
        out.put("count", rows.size());
        out.put("documents", rows);
        return out;
    }
}
