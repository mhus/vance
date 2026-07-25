package de.mhus.vance.brain.prak;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Detects "no substance" chat turns — short acknowledgements
 * ({@link Key#ACK}) and assistant self-narration
 * ({@link Key#SELF_NARRATION}). Used by {@link CheapPathFilter}
 * (skip-or-analyse gate), {@link SpanStrengthDeriver} (trivial-pattern
 * downgrade to {@code STRENGTH:weak}) and the compaction
 * {@code StrengthAwareSelector} optimistic fallback. See
 * {@code planning/memory-evaluation-pipeline.md} §4b.2.
 *
 * <p>The phrase catalogue is bundled as a classpath resource
 * ({@code keywords/trivial-patterns.yaml}) grouped by {@link Key} and
 * language — mirroring {@link HotPathMarkerDetector}. Adding a phrase —
 * or a whole language — is a one-line YAML edit, no code change.
 *
 * <p><b>Language semantics.</b> The English (<code>en</code>) baseline
 * is <i>always</i> active. On top of it, the phrases of the resolved
 * {@code chat.language} are added when a language is passed to
 * {@link #isAck(String, String)} / {@link #isSelfNarration(String, String)}.
 * The effective match set is therefore {@code table['en'] ∪
 * table[lang]}. There is no cascade — the catalogue is bundled-only.
 *
 * <p>{@link Key#ACK} matches the whole message
 * ({@code ^\s*(<alt>)\s*[.!]*\s*$}, via {@link java.util.regex.Matcher#matches()});
 * {@link Key#SELF_NARRATION} matches the message prefix
 * ({@code ^\s*(<alt>)\b}, via {@link java.util.regex.Matcher#find()}).
 *
 * <p>Stateless and thread-safe; the compiled patterns are reused.
 */
@Service
@Slf4j
public class TrivialPatterns {

    /** Baseline language whose phrases are always active. */
    static final String BASELINE_LANG = "en";

    private static final String CLASSPATH = "keywords/trivial-patterns.yaml";

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;

    /** The trivial-pattern families, each with its own match semantics. */
    private enum Key {
        /** Whole message is just an acknowledgement — full-match. */
        ACK,
        /** Assistant self-narration opener — message-prefix match. */
        SELF_NARRATION
    }

    /** Compiled patterns keyed by family, then by lowercase language code. */
    private final Map<Key, Map<String, Pattern>> patterns;

    public TrivialPatterns() {
        this(new ClassPathResource(CLASSPATH));
    }

    /** Test-friendly constructor. */
    TrivialPatterns(Resource resource) {
        this.patterns = load(resource);
        log.info("TrivialPatterns: loaded {} pattern family(ies) from {}",
                patterns.size(), resource);
    }

    /**
     * Short acknowledgement ("ok", "ja", "thanks", "👍") with no further
     * content — the whole message must be the ack.
     *
     * @param lang resolved {@code chat.language} (e.g. {@code "de"},
     *     {@code "de-DE"}); {@code null} = English baseline only.
     */
    public boolean isAck(String text, @Nullable String lang) {
        return matches(Key.ACK, text, lang);
    }

    /**
     * Assistant self-narration ("Ich werde jetzt …", "Let me check …") —
     * matched at the start of the message.
     *
     * @param lang resolved {@code chat.language}; {@code null} = English
     *     baseline only.
     */
    public boolean isSelfNarration(String text, @Nullable String lang) {
        return matches(Key.SELF_NARRATION, text, lang);
    }

    /**
     * True when the message matches the baseline {@code en} pattern of
     * {@code key}, or — when {@code lang} is non-blank, not {@code en},
     * and present in the catalogue — the resolved language's pattern.
     */
    private boolean matches(Key key, String text, @Nullable String lang) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        Map<String, Pattern> byLang = patterns.get(key);
        Pattern baseline = byLang.get(BASELINE_LANG);
        if (baseline != null && test(key, baseline, text)) {
            return true;
        }
        String normalized = normalizeLang(lang);
        if (normalized != null && !BASELINE_LANG.equals(normalized)) {
            Pattern extra = byLang.get(normalized);
            if (extra != null && test(key, extra, text)) {
                return true;
            }
        }
        return false;
    }

    /** Applies the family-specific match mode: ACK = full, else prefix. */
    private static boolean test(Key key, Pattern pattern, String text) {
        return key == Key.ACK
                ? pattern.matcher(text).matches()
                : pattern.matcher(text).find();
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
     * Loads and compiles the bundled trivial-pattern catalogue. Fails
     * fast with a clear {@link IllegalStateException} when the resource
     * is missing, unreadable, malformed, references an unknown key,
     * carries a blank phrase, or lacks the {@code en} baseline for any
     * family — a broken bundled resource is a deployment defect, not a
     * runtime condition to swallow.
     */
    private static Map<Key, Map<String, Pattern>> load(Resource resource) {
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "TrivialPatterns: catalogue not found: " + resource);
        }
        Object parsed;
        try (InputStream in = resource.getInputStream()) {
            parsed = new Yaml().load(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "TrivialPatterns: failed to read catalogue " + resource, e);
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalStateException(
                    "TrivialPatterns: catalogue root is not a map: " + resource);
        }
        Object patternsNode = root.get("patterns");
        if (!(patternsNode instanceof Map<?, ?> families)) {
            throw new IllegalStateException(
                    "TrivialPatterns: catalogue has no 'patterns' map: " + resource);
        }

        Map<Key, Map<String, Pattern>> acc = new EnumMap<>(Key.class);
        for (Map.Entry<?, ?> famEntry : families.entrySet()) {
            String keyName = String.valueOf(famEntry.getKey());
            Key key = parseKey(keyName, resource);
            if (!(famEntry.getValue() instanceof Map<?, ?> langs)) {
                throw new IllegalStateException(
                        "TrivialPatterns: family '" + keyName
                                + "' is not a language map in " + resource);
            }
            Map<String, Pattern> byLang = new LinkedHashMap<>();
            for (Map.Entry<?, ?> langEntry : langs.entrySet()) {
                String lang = String.valueOf(langEntry.getKey())
                        .trim().toLowerCase(Locale.ROOT);
                if (!(langEntry.getValue() instanceof List<?> phrases)) {
                    throw new IllegalStateException(
                            "TrivialPatterns: family '" + keyName + "' language '"
                                    + lang + "' is not a list in " + resource);
                }
                List<String> cleaned = new ArrayList<>(phrases.size());
                for (Object phraseRaw : phrases) {
                    String phrase = String.valueOf(phraseRaw).trim();
                    if (phrase.isEmpty()) {
                        throw new IllegalStateException(
                                "TrivialPatterns: blank phrase in family '" + keyName
                                        + "' language '" + lang + "' of " + resource);
                    }
                    cleaned.add(phrase);
                }
                byLang.put(lang, compile(key, cleaned));
            }
            if (!byLang.containsKey(BASELINE_LANG)) {
                throw new IllegalStateException(
                        "TrivialPatterns: family '" + keyName + "' has no baseline '"
                                + BASELINE_LANG + "' entries in " + resource);
            }
            acc.put(key, Map.copyOf(byLang));
        }

        for (Key key : Key.values()) {
            if (!acc.containsKey(key)) {
                throw new IllegalStateException(
                        "TrivialPatterns: catalogue is missing family '" + key
                                + "' in " + resource);
            }
        }
        return acc;
    }

    private static Key parseKey(String name, Resource resource) {
        try {
            return Key.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "TrivialPatterns: unknown pattern family '" + name
                            + "' in " + resource, e);
        }
    }

    /**
     * Builds the per-family, per-language pattern from the literal
     * phrases. Phrases are {@link Pattern#quote(String) quoted} and
     * joined with {@code |}; the family decides the surrounding anchors.
     */
    private static Pattern compile(Key key, List<String> phrases) {
        String alt = phrases.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        String regex = switch (key) {
            case ACK -> "^\\s*(" + alt + ")\\s*[.!]*\\s*$";
            case SELF_NARRATION -> "^\\s*(" + alt + ")\\b";
        };
        return Pattern.compile(regex, FLAGS);
    }
}
