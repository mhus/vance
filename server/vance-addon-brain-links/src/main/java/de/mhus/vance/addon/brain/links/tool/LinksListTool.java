package de.mhus.vance.addon.brain.links.tool;

import de.mhus.vance.addon.brain.links.LinkEntry;
import de.mhus.vance.addon.brain.links.LinkUrls;
import de.mhus.vance.addon.brain.links.LinksConfig;
import de.mhus.vance.addon.brain.links.LinksStore;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Read a link list.
 *
 * <p>Returns what is <em>stored</em>, not what a card shows: no teaser or
 * picture is fetched here. An agent asked "what is in this list" needs the
 * inventory, and resolving previews for fifty entries would spend fifty
 * foreign requests to decorate an answer that names them by title anyway.
 * To read a page, follow the URL with {@code web_fetch}.
 */
@Component
@Slf4j
public class LinksListTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The link-list folder (holding _app.yaml)."));
                put("group", Map.of("type", "string",
                        "description", "Only this group. Empty string selects the "
                                + "entries that are in no group."));
                put("query", Map.of("type", "string",
                        "description", "Case-insensitive substring filter over title, "
                                + "URL, teaser, note and tags."));
                put("limit", Map.of("type", "integer",
                        "description", "Maximum entries to return. Default 50."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final LinksStore store;

    public LinksListTool(EddieContext eddieContext, LinksStore store) {
        this.eddieContext = eddieContext;
        this.store = store;
    }

    @Override public String name() { return "links_list"; }

    @Override
    public String description() {
        return "Read the entries of a link list: url, title, group, tags, own teaser and "
                + "note. Optionally filtered by group or a substring. Shows what is stored — "
                + "use web_fetch on a URL to read the page itself.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read", "document", "links");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = LinksToolSupport.paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        // rawString, not paramString: "" is a meaningful selector here (the
        // ungrouped entries), while an absent key means "every group".
        String group = LinksToolSupport.rawString(params, "group");
        String query = LinksToolSupport.paramString(params, "query");
        int limit = limit(params.get("limit"));

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        LinksConfig config =
                store.load(ctx.tenantId(), project.getName(), folder).config();

        List<LinkEntry> source = group == null
                ? config.entries() : config.entriesOf(group);
        List<Map<String, Object>> rows = new ArrayList<>();
        int matched = 0;
        for (LinkEntry e : source) {
            if (!matches(e, query)) continue;
            matched++;
            if (rows.size() >= limit) continue;
            rows.add(row(e));
        }

        log.info("LinksListTool folder='{}' group='{}' returned={}/{}",
                folder, group == null ? "*" : group, rows.size(), matched);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("folder", LinksStore.normaliseFolder(folder));
        result.put("groups", config.orderedGroups());
        result.put("total", matched);
        result.put("entries", rows);
        if (matched > rows.size()) {
            result.put("truncated", "Showing " + rows.size() + " of " + matched
                    + " — raise 'limit' or narrow with 'group'/'query'.");
        }
        return result;
    }

    private static Map<String, Object> row(LinkEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", e.url());
        m.put("title", e.displayTitle());
        m.put("host", LinkUrls.hostLabel(e.url()));
        if (e.group() != null) m.put("group", e.group());
        if (e.teaser() != null) m.put("teaser", e.teaser());
        if (e.note() != null) m.put("note", e.note());
        if (!e.tags().isEmpty()) m.put("tags", e.tags());
        if (e.addedAt() != null) m.put("addedAt", e.addedAt().toString());
        return m;
    }

    private static boolean matches(LinkEntry e, @Nullable String query) {
        if (query == null) return true;
        String q = query.toLowerCase(Locale.ROOT);
        return contains(e.displayTitle(), q)
                || contains(e.url(), q)
                || contains(e.teaser(), q)
                || contains(e.note(), q)
                || e.tags().stream().anyMatch(t -> contains(t, q));
    }

    private static boolean contains(@Nullable String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static int limit(@Nullable Object raw) {
        int value = DEFAULT_LIMIT;
        if (raw instanceof Number n) {
            value = n.intValue();
        } else if (raw instanceof String s && !s.isBlank()) {
            try {
                value = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                value = DEFAULT_LIMIT;
            }
        }
        return Math.min(Math.max(value, 1), MAX_LIMIT);
    }
}
