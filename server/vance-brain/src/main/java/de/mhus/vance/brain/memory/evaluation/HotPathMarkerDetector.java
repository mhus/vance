package de.mhus.vance.brain.memory.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Detects hot-path marker phrases in a chat message — the words that
 * trigger an immediate, cheap-tier analyzer pass (see {@code
 * planning/memory-evaluation-pipeline.md} §4a.3).
 *
 * <p>The catalogue covers German and English variants of the
 * memorize / forget / future-rule / punctual / revoke families.
 * Matches respect Unicode word boundaries (so {@code vergiss} does
 * not match the prefix of {@code vergisslich}).
 *
 * <p>Stateless and thread-safe.
 */
@Service
public class HotPathMarkerDetector {

    private static final int PATTERN_FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;

    private record CompiledMarker(
            String marker, MarkerCategory category, Pattern pattern) {
    }

    private static final List<CompiledMarker> MARKERS = compile();

    private static List<CompiledMarker> compile() {
        List<CompiledMarker> out = new ArrayList<>();

        // MEMORIZE
        addMarker(out, "merk dir", MarkerCategory.MEMORIZE);
        addMarker(out, "merke dir", MarkerCategory.MEMORIZE);
        addMarker(out, "erinnere mich", MarkerCategory.MEMORIZE);
        addMarker(out, "remember", MarkerCategory.MEMORIZE);
        addMarker(out, "remind me", MarkerCategory.MEMORIZE);
        addMarker(out, "notiere", MarkerCategory.MEMORIZE);
        addMarker(out, "vermerke", MarkerCategory.MEMORIZE);

        // FORGET
        addMarker(out, "vergiss", MarkerCategory.FORGET);
        addMarker(out, "vergesse", MarkerCategory.FORGET);
        addMarker(out, "forget", MarkerCategory.FORGET);

        // FUTURE_RULE
        addMarker(out, "ab jetzt", MarkerCategory.FUTURE_RULE);
        addMarker(out, "ab sofort", MarkerCategory.FUTURE_RULE);
        addMarker(out, "in zukunft", MarkerCategory.FUTURE_RULE);
        addMarker(out, "von nun an", MarkerCategory.FUTURE_RULE);
        addMarker(out, "künftig", MarkerCategory.FUTURE_RULE);
        addMarker(out, "from now on", MarkerCategory.FUTURE_RULE);
        addMarker(out, "going forward", MarkerCategory.FUTURE_RULE);
        addMarker(out, "neue regel", MarkerCategory.FUTURE_RULE);
        addMarker(out, "new rule", MarkerCategory.FUTURE_RULE);

        // PUNCTUAL — session-scoped, not a long-term rule
        addMarker(out, "diesmal", MarkerCategory.PUNCTUAL);
        addMarker(out, "this time", MarkerCategory.PUNCTUAL);

        // REVOKE
        addMarker(out, "nicht mehr", MarkerCategory.REVOKE);
        addMarker(out, "nie wieder", MarkerCategory.REVOKE);
        addMarker(out, "no longer", MarkerCategory.REVOKE);
        addMarker(out, "never again", MarkerCategory.REVOKE);

        return List.copyOf(out);
    }

    private static void addMarker(
            List<CompiledMarker> out, String phrase, MarkerCategory category) {
        // Match the literal phrase (escaped) bracketed by Unicode word
        // boundaries. \b before/after ensures whole-word match.
        String regex = "\\b" + Pattern.quote(phrase) + "\\b";
        out.add(new CompiledMarker(phrase, category, Pattern.compile(regex, PATTERN_FLAGS)));
    }

    /** Returns all marker matches in {@code text}, in ascending position order. */
    public List<MarkerMatch> detect(String text) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        List<MarkerMatch> matches = new ArrayList<>();
        for (CompiledMarker m : MARKERS) {
            var matcher = m.pattern().matcher(text);
            while (matcher.find()) {
                matches.add(new MarkerMatch(m.marker(), m.category(), matcher.start()));
            }
        }
        matches.sort((a, b) -> Integer.compare(a.position(), b.position()));
        return matches;
    }

    /** True if at least one marker fires in {@code text}. */
    public boolean hasMarker(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        for (CompiledMarker m : MARKERS) {
            if (m.pattern().matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
