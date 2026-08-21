package de.mhus.vance.brain.tools.jaglan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Ask the mounted sources to search their own catalogues.
 *
 * <p><b>Not the same thing as {@code doc_search}.</b> That one queries our
 * Mongo and our embeddings; mounted content is in neither — indexing a foreign
 * library into our own vector store is not something we do. What the library
 * *can* do is search itself, usually far better than a tree walk would, so
 * this delegates.
 *
 * <p>The consequence worth stating in the tool description: a mount that
 * declares no search capability contributes nothing here, and that is
 * reported rather than hidden. Otherwise an agent reads "no results" as "the
 * book is not there".
 */
@Component
@RequiredArgsConstructor
public class MountSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of("type", "string",
                            "description", "What to look for. Passed to the external source as-is; "
                                    + "its own search syntax applies."),
                    "projectId", Map.of("type", "string",
                            "description", "Optional project name. Defaults to the active project."),
                    "mount", Map.of("type", "string",
                            "description", "Restrict to one mount by name. Omit to ask every "
                                    + "mount that supports search."),
                    "limit", Map.of("type", "integer",
                            "description", "Maximum results, default " + DEFAULT_LIMIT
                                    + ", capped at " + MAX_LIMIT + ".")),
            "required", List.of("query"));

    private final KindToolSupport support;

    @Override public String name() { return "mount_search"; }

    @Override public String description() {
        return "Search inside mounted external sources (document libraries, archives) by asking "
                + "them to search their own catalogue. Use for content under '_ext/…', which "
                + "doc_search does NOT cover — mounted files are not indexed here. Returns paths "
                + "you can then read with doc_read. Mounts that do not support search are listed "
                + "in `notSearched`: for those, browse with mount_list instead of concluding the "
                + "file does not exist.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("read-only", "mount", "search"); }

    @Override public String searchHint() {
        return "Search a mounted external library or archive for a file";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        String query = KindToolSupport.paramString(params, "query");
        String mount = KindToolSupport.paramString(params, "mount");
        Integer requested = KindToolSupport.paramInt(params, "limit");
        int limit = Math.max(1, Math.min(requested == null ? DEFAULT_LIMIT : requested, MAX_LIMIT));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        if (query == null || query.isBlank()) {
            out.put("error", "query is required");
            return out;
        }

        List<MountedSource> mounts = support.documentService()
                .listMounts(ctx.tenantId(), project.getName());
        if (mounts.isEmpty()) {
            out.put("count", 0);
            out.put("results", List.of());
            out.put("hint", "This project has no mounted external sources.");
            return out;
        }

        List<DocumentDocument> hits = support.documentService()
                .searchMounted(ctx.tenantId(), project.getName(), mount, query, limit);

        List<Map<String, Object>> rows = new ArrayList<>(hits.size());
        for (DocumentDocument doc : hits) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("path", doc.getPath());
            r.put("name", doc.getName());
            if (doc.getTitle() != null) r.put("title", doc.getTitle());
            if (doc.getMimeType() != null) r.put("mimeType", doc.getMimeType());
            r.put("size", doc.getSize());
            rows.add(r);
        }
        out.put("query", query);
        out.put("count", rows.size());
        out.put("results", rows);

        // Which mounts could not contribute, and why. A silent omission here
        // is the difference between "not found" and "not looked for".
        List<String> notSearched = new ArrayList<>();
        for (MountedSource source : mounts) {
            if (mount != null && !mount.equals(source.name())) continue;
            if (source.statusText() != null) {
                notSearched.add(source.name() + " (" + source.statusText() + ")");
            }
        }
        if (!notSearched.isEmpty()) {
            out.put("notSearched", notSearched);
        }
        return out;
    }
}
