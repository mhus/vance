package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.brain.centauri.CentauriPageRequest;
import de.mhus.vance.brain.centauri.FeedStream;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.feed.FeedFilter;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Typed, lenient view over the {@code config.feeds} block of a feeds manifest.
 *
 * <p>Malformed fields degrade to defaults rather than throwing: a feed with one
 * broken stream still opens with the others, so the reader can repair it in the
 * configuration tab instead of staring at an error page.
 *
 * <p><b>{@code since} is stored relative</b> ({@code -7d}) and resolved per
 * request. An absolute instant in a stored configuration silently stops matching
 * as it ages — a feed configured "last week" in August would still mean August
 * in December. An absolute value is still accepted for the case where somebody
 * really means one fixed date.
 */
public record FeedsConfig(
        List<FeedStream> streams,
        @Nullable String text,
        Set<String> languages,
        List<String> include,
        List<String> exclude,
        @Nullable String since,
        int pageSize) {

    public static final String APP_NAME = "feeds";

    public FeedsConfig {
        streams = streams == null ? List.of() : List.copyOf(streams);
        languages = languages == null ? Set.of() : Set.copyOf(languages);
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        if (pageSize <= 0) {
            pageSize = CentauriPageRequest.DEFAULT_PAGE_SIZE;
        }
    }

    public static FeedsConfig empty() {
        return new FeedsConfig(List.of(), null, Set.of(), List.of(), List.of(), null,
                CentauriPageRequest.DEFAULT_PAGE_SIZE);
    }

    /** Parse the {@code feeds} block out of an application manifest. */
    public static FeedsConfig from(ApplicationDocument doc) {
        Object blockRaw = doc.config().get(APP_NAME);
        if (!(blockRaw instanceof Map<?, ?> block)) {
            return empty();
        }

        List<FeedStream> streams = new ArrayList<>();
        if (block.get("streams") instanceof List<?> list) {
            for (Object entry : list) {
                FeedStream stream = toStream(entry);
                if (stream != null) {
                    streams.add(stream);
                }
            }
        }

        String text = null;
        Set<String> languages = Set.of();
        List<String> include = List.of();
        List<String> exclude = List.of();
        String since = null;
        if (block.get("filter") instanceof Map<?, ?> filter) {
            text = asString(filter.get("text"));
            languages = asStringSet(filter.get("languages"));
            include = asStringList(filter.get("include"));
            exclude = asStringList(filter.get("exclude"));
            since = asString(filter.get("since"));
        }

        int pageSize = CentauriPageRequest.DEFAULT_PAGE_SIZE;
        if (block.get("pageSize") instanceof Number n) {
            pageSize = n.intValue();
        }

        return new FeedsConfig(streams, text, languages, include, exclude, since, pageSize);
    }

    /** The filter as of {@code now} — see the note on relative {@code since}. */
    public FeedFilter toFilter(Instant now) {
        return new FeedFilter(text, languages, include, exclude, resolveSince(now));
    }

    /** Serialise back into the {@code config.feeds} shape. */
    public Map<String, Object> toBlock() {
        Map<String, Object> filter = new java.util.LinkedHashMap<>();
        if (text != null) {
            filter.put("text", text);
        }
        if (!languages.isEmpty()) {
            filter.put("languages", new ArrayList<>(languages));
        }
        if (!include.isEmpty()) {
            filter.put("include", include);
        }
        if (!exclude.isEmpty()) {
            filter.put("exclude", exclude);
        }
        if (since != null) {
            filter.put("since", since);
        }

        List<Map<String, Object>> streamList = new ArrayList<>(streams.size());
        for (FeedStream stream : streams) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("source", stream.sourceId());
            if (!stream.selector().isEmpty()) {
                entry.put("selector", stream.selector());
            }
            streamList.add(entry);
        }

        Map<String, Object> block = new java.util.LinkedHashMap<>();
        block.put("streams", streamList);
        if (!filter.isEmpty()) {
            block.put("filter", filter);
        }
        block.put("pageSize", pageSize);
        return block;
    }

    /**
     * Resolve {@code since} against {@code now}. Understands {@code -7d},
     * {@code -12h}, {@code -30m} and an absolute ISO instant; anything else is
     * ignored rather than fatal, because a typo in a filter should not stop the
     * feed from opening.
     */
    @Nullable Instant resolveSince(Instant now) {
        if (since == null) {
            return null;
        }
        String raw = since.trim().toLowerCase(Locale.ROOT);
        if (raw.startsWith("-") && raw.length() > 2) {
            char unit = raw.charAt(raw.length() - 1);
            String digits = raw.substring(1, raw.length() - 1);
            try {
                long amount = Long.parseLong(digits);
                return switch (unit) {
                    case 'd' -> now.minus(Duration.ofDays(amount));
                    case 'h' -> now.minus(Duration.ofHours(amount));
                    case 'm' -> now.minus(Duration.ofMinutes(amount));
                    default -> null;
                };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Instant.parse(since.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static @Nullable FeedStream toStream(@Nullable Object raw) {
        if (raw instanceof String s && !s.isBlank()) {
            // Short form: a bare source id, meaning that source's default stream.
            return new FeedStream(s.trim(), "");
        }
        if (raw instanceof Map<?, ?> map) {
            String source = asString(map.get("source"));
            if (source == null) {
                return null;
            }
            String selector = asString(map.get("selector"));
            return new FeedStream(source, selector == null ? "" : selector);
        }
        return null;
    }

    private static @Nullable String asString(@Nullable Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static List<String> asStringList(@Nullable Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            String s = asString(o);
            if (s != null) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static Set<String> asStringSet(@Nullable Object v) {
        return Set.copyOf(new LinkedHashSet<>(asStringList(v)));
    }
}
