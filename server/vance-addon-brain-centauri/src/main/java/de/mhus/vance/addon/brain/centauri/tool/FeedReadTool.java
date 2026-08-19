package de.mhus.vance.addon.brain.centauri.tool;

import de.mhus.vance.addon.brain.centauri.FeedsApplication;
import de.mhus.vance.addon.brain.centauri.FeedsConfig;
import de.mhus.vance.brain.centauri.CentauriItem;
import de.mhus.vance.brain.centauri.CentauriNote;
import de.mhus.vance.brain.centauri.CentauriPage;
import de.mhus.vance.brain.centauri.CentauriPageRequest;
import de.mhus.vance.brain.centauri.CentauriService;
import de.mhus.vance.brain.centauri.FeedStream;
import de.mhus.vance.brain.prompt.UntrustedContent;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedScope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Read the most recent entries of a feed.
 *
 * <p><b>No cursor in this contract.</b> A model does not paginate; it wants "the
 * last N since T". Exposing the opaque bundle cursor would invite it to
 * fabricate one, and a fabricated cursor is rejected — so the tool takes a time
 * window instead and the caller never sees paging at all.
 *
 * <p>The use case this exists for is the standing digest: a scheduled job reads
 * the night's entries and writes a summary into the inbox. That is also why the
 * output is deliberately compact — every field costs the reader tokens.
 */
@Component
@Slf4j
public class FeedReadTool implements Tool {

    /** Ceiling regardless of what the caller asks for — a page lands in a prompt. */
    static final int MAX_LIMIT = 50;

    static final int DEFAULT_LIMIT = 20;

    /** Summaries are trimmed: a full wiki edit comment can be longer than the entry. */
    static final int SUMMARY_LIMIT = 300;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Folder of an existing feed app — reads its stored "
                                + "streams and filter. Either this or 'streams'."));
                put("streams", Map.of("type", "array",
                        "description", "Read these streams instead of a stored feed, each "
                                + "`{ source, selector? }`. Source ids come from "
                                + "feed_sources — never guess one.",
                        "items", Map.of("type", "object")));
                put("since", Map.of("type", "string",
                        "description", "Only entries newer than this: relative ('-24h', "
                                + "'-7d', '-30m') or an ISO instant. Overrides a stored "
                                + "filter's window."));
                put("languages", Map.of("type", "array",
                        "description", "Restrict to these language codes, e.g. ['de','en']. "
                                + "Entries whose source declares no language always pass.",
                        "items", Map.of("type", "string")));
                put("limit", Map.of("type", "integer",
                        "description", "Entries to return, default " + DEFAULT_LIMIT
                                + ", max " + MAX_LIMIT + "."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of());

    private final EddieContext eddieContext;
    private final CentauriService centauriService;
    private final FeedsApplication application;

    public FeedReadTool(EddieContext eddieContext,
                        CentauriService centauriService,
                        FeedsApplication application) {
        this.eddieContext = eddieContext;
        this.centauriService = centauriService;
        this.application = application;
    }

    @Override public String name() { return "feed_read"; }

    @Override
    public String description() {
        return "Read the most recent entries of a feed — either an existing feed app "
                + "(by folder) or an ad-hoc set of streams. Returns title, link, time, "
                + "source and a short summary per entry. Use for digests and "
                + "'what happened since…' questions, not for searching (that is "
                + "research_search).";
    }

    @Override public boolean primary() { return false; }

    @Override public boolean deferred() { return true; }

    @Override public String searchHint() {
        return "read recent entries of a news/wiki/data feed, build a digest";
    }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read-only", "feeds");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        FeedScope scope = new FeedScope(
                ctx.tenantId(), project.getName(), ctx.processId(), ctx.userId());
        Instant now = Instant.now();

        String folder = asString(params.get("folder"));
        List<FeedStream> explicit = toStreams(params.get("streams"));
        if (folder == null && explicit.isEmpty()) {
            throw new ToolException("either folder or streams is required — "
                    + "call feed_sources to see which source ids exist");
        }

        List<FeedStream> streams;
        FeedFilter filter;
        if (!explicit.isEmpty()) {
            streams = explicit;
            filter = new FeedFilter(null, languages(params), List.of(), List.of(),
                    resolveSince(asString(params.get("since")), now));
        } else {
            FeedsConfig stored = application.readConfig(ctx.tenantId(), project.getName(), folder);
            if (stored.streams().isEmpty()) {
                throw new ToolException("the feed at '" + folder + "' has no streams configured");
            }
            streams = stored.streams();
            filter = merge(stored, params, now);
        }

        int limit = Math.min(intValue(params.get("limit"), DEFAULT_LIMIT), MAX_LIMIT);
        CentauriPage page = centauriService.fetchPage(
                new CentauriPageRequest(streams, filter, limit, FeedDirection.OLDER, null),
                scope);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.getName());
        out.put("count", page.items().size());
        out.put("items", page.items().stream().map(FeedReadTool::toMap).toList());
        if (!page.notes().isEmpty()) {
            List<String> notes = new ArrayList<>();
            for (CentauriNote note : page.notes()) {
                notes.add(note.sourceId() + (note.selector().isEmpty() ? "" : "/" + note.selector())
                        + ": " + note.kind().name().toLowerCase(java.util.Locale.ROOT));
            }
            // Surfaced, not swallowed: a silently missing source reads as a source
            // with no news, and a digest would then omit it without saying so.
            out.put("unavailable", notes);
        }
        if (page.items().isEmpty()) {
            out.put("hint", "No entries in this window. Widen 'since' or check "
                    + "'unavailable' — an empty result is not proof that nothing happened.");
        }
        log.debug("FeedReadTool project='{}' streams={} returned={}",
                project.getName(), streams.size(), page.items().size());
        return out;
    }

    // ── internals ────────────────────────────────────────────────────

    /**
     * One entry as the model sees it.
     *
     * <p>Every field a source wrote — title, summary, author, language — goes
     * through {@link UntrustedContent#collapseWhitespace}. This is foreign text
     * on its way into a prompt, and left as it arrived it can introduce line
     * breaks and headings where the surrounding template has structure. Same
     * rule and same reason as {@code SearchHitRows} on the research side; the
     * two surfaces must not differ in how much they trust a stranger.
     */
    private static Map<String, Object> toMap(CentauriItem entry) {
        FeedItem item = entry.item();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", UntrustedContent.collapseWhitespace(item.title()));
        map.put("url", item.url());
        map.put("publishedAt", item.publishedAt().toString());
        map.put("source", entry.sourceId());
        if (!entry.selector().isEmpty()) {
            map.put("stream", entry.selector());
        }
        if (item.language() != null) {
            map.put("language", UntrustedContent.collapseWhitespace(item.language()));
        }
        if (item.author() != null) {
            map.put("author", UntrustedContent.collapseWhitespace(item.author()));
        }
        String summary = item.summary();
        if (summary != null && !summary.isBlank()) {
            String flat = UntrustedContent.collapseWhitespace(summary);
            map.put("summary", flat.length() <= SUMMARY_LIMIT
                    ? flat : flat.substring(0, SUMMARY_LIMIT) + "…");
        }
        return map;
    }

    /**
     * Tool parameters win over the stored filter, because the caller asked now and
     * the manifest was written earlier — but only where they were given.
     */
    private static FeedFilter merge(FeedsConfig stored, Map<String, Object> params, Instant now) {
        Set<String> languages = languages(params);
        Instant since = resolveSince(asString(params.get("since")), now);
        return new FeedFilter(
                stored.text(),
                languages.isEmpty() ? stored.languages() : languages,
                stored.include(),
                stored.exclude(),
                since != null ? since : stored.resolveSince(now));
    }

    /** Understands {@code -7d}/{@code -12h}/{@code -30m} and an ISO instant. */
    static @Nullable Instant resolveSince(@Nullable String raw, Instant now) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("-") && value.length() > 2) {
            char unit = value.charAt(value.length() - 1);
            try {
                long amount = Long.parseLong(value.substring(1, value.length() - 1));
                return switch (unit) {
                    case 'd' -> now.minus(Duration.ofDays(amount));
                    case 'h' -> now.minus(Duration.ofHours(amount));
                    case 'm' -> now.minus(Duration.ofMinutes(amount));
                    default -> null;
                };
            } catch (NumberFormatException e) {
                throw new ToolException("since must be like '-24h', '-7d' or an ISO instant, "
                        + "was '" + raw + "'");
            }
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new ToolException("since must be like '-24h', '-7d' or an ISO instant, "
                    + "was '" + raw + "'");
        }
    }

    private static List<FeedStream> toStreams(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<FeedStream> out = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof String s && !s.isBlank()) {
                out.add(new FeedStream(s.trim(), ""));
            } else if (entry instanceof Map<?, ?> map) {
                String source = asString(map.get("source"));
                if (source == null) {
                    throw new ToolException("each stream needs a 'source'");
                }
                String selector = asString(map.get("selector"));
                out.add(new FeedStream(source, selector == null ? "" : selector));
            }
        }
        return out;
    }

    private static Set<String> languages(Map<String, Object> params) {
        if (!(params.get("languages") instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object entry : list) {
            String value = asString(entry);
            if (value != null) {
                out.add(value.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    private static int intValue(@Nullable Object raw, int fallback) {
        return raw instanceof Number n && n.intValue() > 0 ? n.intValue() : fallback;
    }

    private static @Nullable String asString(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
