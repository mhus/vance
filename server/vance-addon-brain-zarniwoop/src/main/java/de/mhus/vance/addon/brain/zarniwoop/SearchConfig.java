package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code config.search} block of an {@code app: search} manifest.
 *
 * <p><b>Thin on purpose.</b> A search has no state — what a person typed is gone
 * the moment they type something else. What is worth keeping is only the shape of
 * the surface: which modality it opens on, how many hits, and searches somebody
 * wants back without retyping.
 *
 * <p>What is deliberately <b>not</b> here is a history. A search log written
 * without being asked for is a usage trace, and nobody requested one.
 *
 * <p>Reading is lenient in the way every hand-editable document has to be: a
 * manifest is a file a person edits, so an unknown modality, a number as a
 * string or a missing block must degrade to a default rather than break the app
 * that owns the file.
 */
record SearchConfig(
        SearchModality defaultModality,
        int defaultNum,
        List<SavedSearch> savedSearches) {

    /** Block key inside {@code config}. */
    static final String BLOCK = "search";

    static final SearchModality FALLBACK_MODALITY = SearchModality.WEB;
    static final int FALLBACK_NUM = 5;

    /**
     * One search worth keeping. {@code name} is what a person recognises it by;
     * everything else is the query as it would be re-run.
     */
    record SavedSearch(
            String name,
            String query,
            SearchModality modality,
            SearchTier tier,
            @Nullable String instance) {

        SavedSearch {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("saved search needs a name");
            }
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("saved search needs a query");
            }
            modality = modality == null ? FALLBACK_MODALITY : modality;
            tier = tier == null ? SearchTier.NORMAL : tier;
        }
    }

    SearchConfig {
        defaultModality = defaultModality == null ? FALLBACK_MODALITY : defaultModality;
        defaultNum = defaultNum <= 0 ? FALLBACK_NUM : defaultNum;
        savedSearches = savedSearches == null ? List.of() : List.copyOf(savedSearches);
    }

    static SearchConfig empty() {
        return new SearchConfig(FALLBACK_MODALITY, FALLBACK_NUM, List.of());
    }

    /** Read the block out of a manifest, falling back on anything unreadable. */
    static SearchConfig from(ApplicationDocument manifest) {
        if (manifest == null || manifest.config() == null) {
            return empty();
        }
        Object raw = manifest.config().get(BLOCK);
        if (!(raw instanceof Map<?, ?> block)) {
            return empty();
        }
        return new SearchConfig(
                modality(block.get("defaultModality")),
                asInt(block.get("defaultNum")),
                savedSearches(block.get("savedSearches")));
    }

    /** The YAML form written back into {@code config.search}. */
    Map<String, Object> toBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("defaultModality", defaultModality.name().toLowerCase(Locale.ROOT));
        block.put("defaultNum", defaultNum);
        if (!savedSearches.isEmpty()) {
            List<Map<String, Object>> saved = new ArrayList<>(savedSearches.size());
            for (SavedSearch s : savedSearches) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", s.name());
                row.put("query", s.query());
                row.put("modality", s.modality().name().toLowerCase(Locale.ROOT));
                if (s.tier() != SearchTier.NORMAL) {
                    row.put("tier", s.tier().name().toLowerCase(Locale.ROOT));
                }
                if (s.instance() != null && !s.instance().isBlank()) {
                    row.put("instance", s.instance().trim());
                }
                saved.add(row);
            }
            block.put("savedSearches", saved);
        }
        return block;
    }

    // ── lenient reading ──────────────────────────────────────────────

    private static List<SavedSearch> savedSearches(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<SavedSearch> out = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String name = asString(map.get("name"));
            String query = asString(map.get("query"));
            if (name == null || query == null) {
                // A saved search without a name or a query cannot be offered or
                // run; skipping one beats refusing the whole manifest.
                continue;
            }
            out.add(new SavedSearch(name, query,
                    modality(map.get("modality")), tier(map.get("tier")),
                    asString(map.get("instance"))));
        }
        return List.copyOf(out);
    }

    /**
     * An unknown modality falls back rather than throwing. The manifest is
     * hand-editable and the vocabulary is a closed enum: a typo would otherwise
     * take the whole app down, and the app is where you would fix the typo.
     */
    static SearchModality modality(@Nullable Object raw) {
        String s = asString(raw);
        if (s == null) {
            return FALLBACK_MODALITY;
        }
        try {
            return SearchModality.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FALLBACK_MODALITY;
        }
    }

    static SearchTier tier(@Nullable Object raw) {
        String s = asString(raw);
        if (s == null) {
            return SearchTier.NORMAL;
        }
        try {
            return SearchTier.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SearchTier.NORMAL;
        }
    }

    /** Numbers in a hand-written YAML arrive as either. */
    private static int asInt(@Nullable Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        String s = asString(raw);
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static @Nullable String asString(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
