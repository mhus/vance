package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.FolderInfo;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocListFoldersTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Optional project name. Defaults to the active project."),
                    "parentPath", Map.of("type", "string",
                            "description", "Optional parent folder path; empty/omitted lists "
                                    + "folders at every depth.")),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "doc_list_folders"; }
    @Override public String description() {
        return "List virtual folders inside a project. Folders are derived from document paths — "
                + "no separate folder entity exists. Optional `parentPath` restricts the listing "
                + "to that subtree.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("folders", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        String parent = KindToolSupport.paramString(params, "parentPath");
        List<FolderInfo> folders = support.documentService()
                .extractFolders(ctx.tenantId(), project.getName(), parent);
        List<Map<String, Object>> rows = new ArrayList<>(folders.size());
        for (FolderInfo f : folders) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("path", f.path());
            r.put("name", f.name());
            if (f.parentPath() != null) r.put("parentPath", f.parentPath());
            r.put("documentCount", f.documentCount());
            r.put("subfolderCount", f.subfolderCount());
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        if (parent != null) out.put("parentPath", parent);
        out.put("count", rows.size());
        out.put("folders", rows);
        return out;
    }
}
