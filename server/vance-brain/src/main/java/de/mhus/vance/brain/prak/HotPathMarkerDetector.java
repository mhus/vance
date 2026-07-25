package de.mhus.vance.brain.prak;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Detects hot-path marker phrases in a chat message — the words that
 * trigger an immediate, cheap-tier analyzer pass (see {@code
 * planning/memory-evaluation-pipeline.md} §4a.3).
 *
 * <p>The keyword catalogue is bundled as a classpath resource
 * ({@code keywords/markers.yaml}) grouped by {@link MarkerCategory} and
 * language. Adding a phrase — or a whole language — is a one-line YAML
 * edit, no code change.
 *
 * <p><b>Language semantics.</b> The English (<code>en</code>) baseline
 * is <i>always</i> active. On top of it, the phrases of the resolved
 * {@code chat.language} are added when a language is passed to
 * {@link #detect(String, String)} / {@link #hasMarker(String, String)}.
 * The effective match set is therefore {@code table['en'] ∪
 * table[lang]}. There is no cascade — the catalogue is bundled-only.
 *
 * <p>Matches respect Unicode word boundaries (so {@code vergiss} does
 * not match the prefix of {@code vergisslich}).
 *
 * <p>Stateless and thread-safe.
 */
@Service
@Slf4j
public class HotPathMarkerDetector {

    /** Baseline language whose phrases are always active. */
    static final String BASELINE_LANG = "en";

    private static final String CLASSPATH = "keywords/markers.yaml";

    private static final int PATTERN_FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;

    private record CompiledMarker(
            String marker, MarkerCategory category, Pattern pattern) {
    }

    /** Compiled markers keyed by lowercase language code. */
    private final Map<String, List<CompiledMarker>> byLang;

    public HotPathMarkerDetector() {
        this(new ClassPathResource(CLASSPATH));
    }

    /** Test-friendly constructor. */
    HotPathMarkerDetector(Resource resource) {
        this.byLang = load(resource);
        log.info("HotPathMarkerDetector: loaded {} language(s) from {}",
                byLang.size(), resource);
    }

    /**
     * Returns all marker matches in {@code text}, in ascending position
     * order. Baseline {@code en} plus (when {@code lang} is non-blank,
     * not {@code en}, and present in the catalogue) the phrases of
     * {@code lang}.
     *
     * @param lang resolved {@code chat.language} (e.g. {@code "de"},
     *     {@code "de-DE"}); {@code null} = English baseline only.
     */
    public List<MarkerMatch> detect(String text, @Nullable String lang) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        List<MarkerMatch> matches = new ArrayList<>();
        for (List<CompiledMarker> markers : activeMarkers(lang)) {
            for (CompiledMarker m : markers) {
                var matcher = m.pattern().matcher(text);
                while (matcher.find()) {
                    matches.add(new MarkerMatch(m.marker(), m.category(), matcher.start()));
                }
            }
        }
        matches.sort((a, b) -> Integer.compare(a.position(), b.position()));
        return matches;
    }

    /**
     * True if at least one marker fires in {@code text}, using the
     * baseline {@code en} plus the resolved {@code lang} phrases.
     *
     * @param lang resolved {@code chat.language}; {@code null} =
     *     English baseline only.
     */
    public boolean hasMarker(String text, @Nullable String lang) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        for (List<CompiledMarker> markers : activeMarkers(lang)) {
            for (CompiledMarker m : markers) {
                if (m.pattern().matcher(text).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The marker lists active for {@code lang}: always the baseline,
     * plus the resolved language when it is a distinct catalogue entry.
     */
    private List<List<CompiledMarker>> activeMarkers(@Nullable String lang) {
        List<List<CompiledMarker>> active = new ArrayList<>(2);
        List<CompiledMarker> baseline = byLang.get(BASELINE_LANG);
        if (baseline != null) {
            active.add(baseline);
        }
        String normalized = normalizeLang(lang);
        if (normalized != null && !BASELINE_LANG.equals(normalized)) {
            List<CompiledMarker> extra = byLang.get(normalized);
            if (extra != null) {
                active.add(extra);
            }
        }
        return active;
    }

    /**
     * Normalizes a language tag to the lowercase primary subtag —
     * {@code "de-DE"} → {@code "de"}, {@code "EN"} → {@code "en"}.
     * Returns {@code null} for blank input.
     */
    private static @Nullable String normalizeLang(@Nullable String lang) {
        if (lang == null || lang.isBlank()) {
            return null;
        }
        String trimmed = lang.trim().toLowerCase(Locale.ROOT);
        int dash = trimmed.indexOf('-');
        return dash >= 0 ? trimmed.substring(0, dash) : trimmed;
    }

    /**
     * Loads and compiles the bundled marker catalogue. Fails fast with a
     * clear {@link IllegalStateException} when the resource is missing,
     * unreadable, malformed, or references an unknown category — a
     * broken bundled resource is a deployment defect, not a runtime
     * condition to swallow.
     */
    private static Map<String, List<CompiledMarker>> load(Resource resource) {
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "HotPathMarkerDetector: marker catalogue not found: " + resource);
        }
        Object parsed;
        try (InputStream in = resource.getInputStream()) {
            parsed = new Yaml().load(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "HotPathMarkerDetector: failed to read marker catalogue "
                            + resource, e);
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalStateException(
                    "HotPathMarkerDetector: marker catalogue root is not a map: "
                            + resource);
        }
        Object markersNode = root.get("markers");
        if (!(markersNode instanceof Map<?, ?> categories)) {
            throw new IllegalStateException(
                    "HotPathMarkerDetector: marker catalogue has no 'markers' map: "
                            + resource);
        }

        // Accumulate per-language marker lists across all categories.
        Map<String, List<CompiledMarker>> acc = new LinkedHashMap<>();
        for (Map.Entry<?, ?> catEntry : categories.entrySet()) {
            String categoryName = String.valueOf(catEntry.getKey());
            MarkerCategory category = parseCategory(categoryName, resource);
            if (!(catEntry.getValue() instanceof Map<?, ?> langs)) {
                throw new IllegalStateException(
                        "HotPathMarkerDetector: category '" + categoryName
                                + "' is not a language map in " + resource);
            }
            for (Map.Entry<?, ?> langEntry : langs.entrySet()) {
                String lang = String.valueOf(langEntry.getKey())
                        .trim().toLowerCase(Locale.ROOT);
                if (!(langEntry.getValue() instanceof List<?> phrases)) {
                    throw new IllegalStateException(
                            "HotPathMarkerDetector: category '" + categoryName
                                    + "' language '" + lang
                                    + "' is not a list in " + resource);
                }
                List<CompiledMarker> target =
                        acc.computeIfAbsent(lang, k -> new ArrayList<>());
                for (Object phraseRaw : phrases) {
                    String phrase = String.valueOf(phraseRaw).trim();
                    if (phrase.isEmpty()) {
                        throw new IllegalStateException(
                                "HotPathMarkerDetector: blank phrase in category '"
                                        + categoryName + "' language '" + lang
                                        + "' of " + resource);
                    }
                    target.add(new CompiledMarker(phrase, category, compile(phrase)));
                }
            }
        }

        // Freeze into immutable structures.
        Map<String, List<CompiledMarker>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<CompiledMarker>> e : acc.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        if (!frozen.containsKey(BASELINE_LANG)) {
            throw new IllegalStateException(
                    "HotPathMarkerDetector: marker catalogue has no baseline '"
                            + BASELINE_LANG + "' entries in " + resource);
        }
        return Map.copyOf(frozen);
    }

    private static MarkerCategory parseCategory(String name, Resource resource) {
        try {
            return MarkerCategory.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "HotPathMarkerDetector: unknown marker category '" + name
                            + "' in " + resource, e);
        }
    }

    /**
     * Match the literal phrase (escaped) bracketed by Unicode word
     * boundaries. {@code \b} before/after ensures a whole-word match.
     */
    private static Pattern compile(String phrase) {
        String regex = "\\b" + Pattern.quote(phrase) + "\\b";
        return Pattern.compile(regex, PATTERN_FLAGS);
    }
}
