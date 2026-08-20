package de.mhus.vance.addon.brain.centauri.tool;

import de.mhus.vance.addon.brain.centauri.FeedsApplication;
import de.mhus.vance.brain.centauri.CentauriException;
import de.mhus.vance.brain.centauri.CentauriService;
import de.mhus.vance.brain.prompt.UntrustedContent;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * One feed entry in full: the body, and whatever the source adds for a single
 * lookup.
 *
 * <p>The counterpart to {@code feed_read}, which produces teasers — twenty of
 * them per call, which is what makes a page affordable. This is the other
 * half: the reader marked one entry and asked about it, and answering needs
 * the text rather than the first three lines.
 *
 * <p>Which entry that is arrives without a tool call. A marked entry rides in
 * the app-context block of the prompt (see {@code FeedsApplication#promptInject}),
 * so the model already knows the source and the id and spends its one call on
 * the content.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeedItemTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "sourceId", Map.of(
                            "type", "string",
                            "description", "Endpoint id of the source, e.g. 'hrafnagud'. "
                                    + "Comes from feed_sources or from the marked entry in "
                                    + "the app context."),
                    "itemId", Map.of(
                            "type", "string",
                            "description", "The entry's id, as the feed reported it.")),
            "required", List.of("sourceId", "itemId"));

    private final EddieContext eddieContext;
    private final CentauriService centauriService;

    @Override
    public String name() {
        return "feed_item";
    }

    @Override
    public String description() {
        return "Read one feed entry in full — its body plus whatever the source "
                + "carries for a single entry. Use it when the reader points at an "
                + "entry ('the selected one', 'that article') or when a teaser from "
                + "feed_read is not enough to answer. The marked entry's source and "
                + "id are in the app context; never invent them.";
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
        return "Full text of one feed entry.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String sourceId = string(params, "sourceId");
        String itemId = string(params, "itemId");
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        FeedScope scope = new FeedScope(
                ctx.tenantId(), project.getName(), ctx.processId(), ctx.userId());

        FeedItem item;
        try {
            item = centauriService.loadItem(sourceId, itemId, scope).orElse(null);
        } catch (CentauriException e) {
            throw new ToolException(e.getMessage());
        }
        if (item == null) {
            // Not an error: an entry can age out of a source between the page
            // and the question about it.
            return Map.of(
                    "found", false,
                    "hint", "The source no longer knows this entry — it may have aged out "
                            + "of the stream. Re-read the feed with feed_read.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("sourceId", sourceId);
        out.put("id", item.id());
        out.put("title", UntrustedContent.collapseWhitespace(item.title()));
        out.put("url", item.url());
        out.put("publishedAt", item.publishedAt().toString());
        if (!StringUtils.isBlank(item.summary())) {
            out.put("summary", UntrustedContent.collapseWhitespace(item.summary()));
        }
        // Everything below is text a stranger wrote, on its way into a prompt.
        // Same rule and same reason as SearchHitRows on the research side: the
        // two surfaces must not differ in how much they trust a source. Extras
        // are as foreign as the title beside them — they arrive verbatim from
        // the far end via OdeFeedInstance.extras() — and leaving one
        // unsanitised is a hole in exactly the wall the sibling fields stand
        // behind.
        if (!StringUtils.isBlank(item.body())) {
            out.put("body", UntrustedContent.collapseWhitespace(item.body()));
        }
        if (!StringUtils.isBlank(item.author())) {
            out.put("author", UntrustedContent.collapseWhitespace(item.author()));
        }
        if (!StringUtils.isBlank(item.language())) {
            out.put("language", UntrustedContent.collapseWhitespace(item.language()));
        }
        if (!item.tags().isEmpty()) out.put("tags", safeTags(item.tags()));
        if (!item.extras().isEmpty()) out.put("extras", safeExtras(item.extras()));
        if (StringUtils.isBlank(item.body())) {
            out.put("bodyHint", "This entry has no full text yet — the source fetches "
                    + "bodies on its own schedule. The summary and the URL are what exists.");
        }
        log.debug("FeedItemTool source='{}' item='{}' body={}",
                sourceId, itemId, item.body() == null ? 0 : item.body().length());
        return out;
    }

    /** Foreign tag strings, collapsed like every other remote string. */
    private static List<String> safeTags(List<String> tags) {
        List<String> out = new ArrayList<>(tags.size());
        for (String tag : tags) {
            out.add(UntrustedContent.collapseWhitespace(tag));
        }
        return out;
    }

    /**
     * Foreign key/value pairs, collapsed. Numbers and booleans pass through —
     * they carry no structure a template could be broken with.
     */
    private static Map<String, Object> safeExtras(Map<String, Object> extras) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : extras.entrySet()) {
            Object value = e.getValue();
            out.put(UntrustedContent.collapseWhitespace(e.getKey()),
                    value == null || value instanceof Number || value instanceof Boolean
                            ? value
                            : UntrustedContent.collapseWhitespace(String.valueOf(value)));
        }
        return out;
    }

    private static String string(Map<String, Object> params, String name) {
        Object raw = params == null ? null : params.get(name);
        if (!(raw instanceof String s) || StringUtils.isBlank(s)) {
            throw new ToolException("'" + name + "' is required");
        }
        return s.trim();
    }
}
