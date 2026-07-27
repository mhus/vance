package de.mhus.vance.brain.tools.foreign;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService.FolderListing;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Browse the documents of another project — folders and files at one level,
 * like a directory listing. Requires READ on the target project. Reserved
 * {@code _}-prefixed namespaces (recipes, settings, trash) are hidden.
 */
@Component
@RequiredArgsConstructor
public class ForeignDocListTool implements Tool {

    private static final int PAGE_SIZE = 200;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Name of the project to browse (required). "
                                    + "Discover via foreign_project_list."),
                    "folder", Map.of("type", "string",
                            "description", "Optional folder path inside that project, e.g. "
                                    + "'documents/notes'. Omit for the project root.")),
            "required", List.of("projectId"));

    private final ForeignAccessSupport foreign;

    @Override public String name() { return "foreign_doc_list"; }

    @Override public String description() {
        return "List documents (and sub-folders) of ANOTHER project in your tenant at one folder "
                + "level. Read-only; requires read access to that project. Use foreign_project_list "
                + "first to find the project name, then foreign_doc_read / foreign_doc_copy on a hit.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("read-only", "cross-project", "document"); }
    @Override public String searchHint() { return "List documents in another project"; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String projectId = KindToolSupport.requireString(params, "projectId");
        String folder = KindToolSupport.paramString(params, "folder");
        ProjectDocument project = foreign.resolveForeign(projectId, ctx, Action.READ);

        FolderListing listing = foreign.documents().listByFolder(
                ctx.tenantId(), project.getName(), folder, null, 0, PAGE_SIZE);

        List<String> folders = new ArrayList<>();
        for (String f : listing.folders()) {
            if (!ForeignAccessSupport.reserved(f)) folders.add(f);
        }
        List<Map<String, Object>> files = new ArrayList<>();
        for (DocumentDocument d : listing.files()) {
            if (ForeignAccessSupport.reserved(d.getPath())) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", d.getId());
            r.put("path", d.getPath());
            if (d.getTitle() != null) r.put("title", d.getTitle());
            if (d.getKind() != null) r.put("kind", d.getKind());
            if (d.getMimeType() != null) r.put("mimeType", d.getMimeType());
            files.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        if (folder != null) out.put("folder", folder);
        out.put("folders", folders);
        out.put("files", files);
        out.put("fileCount", files.size());
        return out;
    }
}
