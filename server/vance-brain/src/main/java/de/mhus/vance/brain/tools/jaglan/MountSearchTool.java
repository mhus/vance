package de.mhus.vance.brain.tools.jaglan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.jaglan.JaglanShellService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Ask the mounted sources to search their own catalogues.
 *
 * <p><b>Not the same thing as {@code doc_find} / {@code doc_grep} /
 * {@code memory_search}.</b> Those query our Mongo and our embeddings; mounted
 * content is in neither — indexing a foreign library into our own vector store
 * is not something we do. What the library *can* do is search itself, usually
 * far better than a tree walk would, so this delegates.
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

    /**
     * Asked per mount, because the outcome is the point.
     *
     * <p>{@code DocumentService.searchMounted} is the convenient call and
     * would be the natural one, but it concatenates the hits of every mount
     * and drops each mount's {@code MountSearchOutcome} on the way — leaving
     * this tool unable to tell "asked, found nothing" from "never asked",
     * which is the one distinction {@code notSearched} exists to report.
     * Optional the same way {@code DocumentService} holds it: a process
     * without Mongo-backed mount support has no bean, and then this project
     * has no mounts either.
     */
    private final ObjectProvider<JaglanShellService> shellServiceProvider;

    @Override public String name() { return "mount_search"; }

    @Override public String description() {
        return "Search inside mounted external sources (document libraries, archives) by asking "
                + "them to search their own catalogue. Use for content under '_ext/…', which "
                + "doc_find, doc_grep and memory_search do NOT cover — mounted files are not "
                + "indexed here. Returns paths you can then read with doc_read. Mounts that do "
                + "not support search are listed in `notSearched`: for those, browse with "
                + "mount_list instead of concluding the file does not exist.";
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
        JaglanShellService shellService = shellServiceProvider.getIfAvailable();
        if (mounts.isEmpty() || shellService == null) {
            out.put("count", 0);
            out.put("results", List.of());
            out.put("hint", "This project has no mounted external sources.");
            return out;
        }

        if (mount != null && mounts.stream().noneMatch(m -> mount.equals(m.name()))) {
            // A misspelled mount name would otherwise return an empty result
            // set — indistinguishable from "the source has nothing".
            out.put("error", "no mount named '" + mount + "' in this project");
            out.put("mounts", mounts.stream().map(MountedSource::name).toList());
            return out;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        // Which mounts could not contribute, and why. A silent omission here
        // is the difference between "not found" and "not looked for" — and
        // the second one is what makes an agent conclude the file is absent.
        List<String> notSearched = new ArrayList<>();

        for (MountedSource source : mounts) {
            if (mount != null && !mount.equals(source.name())) continue;
            if (rows.size() >= limit) {
                // Named rather than dropped: the earlier mounts filled the
                // budget, so this one was never asked and its silence means
                // nothing about what it holds.
                notSearched.add(source.name() + " (result limit reached before it was asked)");
                continue;
            }
            JaglanShellService.MountSearch result = shellService.searchInMount(
                    ctx.tenantId(), project.getName(), source.name(), query,
                    limit - rows.size());
            switch (result.outcome()) {
                case DELEGATED -> {
                    for (DocumentDocument doc : result.hits()) rows.add(row(doc));
                }
                case UNSUPPORTED -> notSearched.add(source.name()
                        + " (does not support search — browse it with mount_list)");
                case UNAVAILABLE -> notSearched.add(source.name() + " ("
                        + (source.statusText() == null ? "did not answer" : source.statusText())
                        + ")");
            }
        }

        out.put("query", query);
        out.put("count", rows.size());
        out.put("results", rows);
        if (!notSearched.isEmpty()) {
            out.put("notSearched", notSearched);
        }
        return out;
    }

    private static Map<String, Object> row(DocumentDocument doc) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", doc.getPath());
        r.put("name", doc.getName());
        if (doc.getTitle() != null) r.put("title", doc.getTitle());
        if (doc.getMimeType() != null) r.put("mimeType", doc.getMimeType());
        r.put("size", doc.getSize());
        return r;
    }
}
