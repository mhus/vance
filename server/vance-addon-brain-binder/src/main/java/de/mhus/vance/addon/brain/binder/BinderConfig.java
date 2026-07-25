package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed, lenient view over the {@code config.binder} block of a binder
 * manifest. Missing / malformed fields degrade to sensible defaults
 * rather than throwing — a binder with a broken sub-block still opens
 * (empty), so the user can repair it.
 *
 * @param landingRef      optional ref opened by default (matches an
 *                        {@code entries[].ref}).
 * @param entries         ordered anchored entries.
 * @param indexOutputPath relative output path of the generated index
 *                        (default {@code _index.md}).
 */
public record BinderConfig(
        @Nullable String landingRef,
        List<BinderEntry> entries,
        String indexOutputPath) {

    public static final String APP_NAME = "binder";
    public static final String DEFAULT_INDEX = "_index.md";

    public BinderConfig {
        if (entries == null) entries = List.of();
        if (indexOutputPath == null || indexOutputPath.isBlank()) {
            indexOutputPath = DEFAULT_INDEX;
        }
    }

    /** Parse the {@code binder} block out of an application manifest. */
    public static BinderConfig from(ApplicationDocument doc) {
        Object blockRaw = doc.config().get(APP_NAME);
        if (!(blockRaw instanceof Map<?, ?> block)) {
            return new BinderConfig(null, List.of(), DEFAULT_INDEX);
        }

        String landingRef = asString(block.get("landingRef"));

        List<BinderEntry> entries = new ArrayList<>();
        if (block.get("entries") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    BinderEntry e = BinderEntry.fromMap(m);
                    if (e != null) entries.add(e);
                } else if (o instanceof String s && !s.isBlank()) {
                    // Short form: a bare ref string.
                    entries.add(new BinderEntry(s.trim(), null, null));
                }
            }
        }

        String indexOutput = DEFAULT_INDEX;
        if (block.get("index") instanceof Map<?, ?> index) {
            String out = asString(index.get("outputPath"));
            if (out != null) indexOutput = out;
        }

        return new BinderConfig(landingRef, entries, indexOutput);
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }
}
