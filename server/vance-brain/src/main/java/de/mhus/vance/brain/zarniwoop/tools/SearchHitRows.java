package de.mhus.vance.brain.zarniwoop.tools;

import de.mhus.vance.brain.prompt.UntrustedContent;
import de.mhus.vance.toolpack.research.ContentInline;
import de.mhus.vance.toolpack.research.ContentReference;
import de.mhus.vance.toolpack.research.SearchHit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link SearchHit} into the map the research tools hand to the model.
 *
 * <p>One place, because there were three: {@code research_search},
 * {@code research_rich} and {@code research_search_expert} each built the same
 * row inline, and only one of them ran the untrusted fields through
 * {@link UntrustedContent#collapseWhitespace}. Three copies of a shaping rule is
 * how two of them end up without a hardening the third has.
 *
 * <p><b>Everything a provider wrote is collapsed, extras included, and the
 * canonical fields are written last.</b> Both halves of that matter: extras are
 * as foreign as the title beside them, and a provider that could put a
 * {@code title} key in extras would otherwise overwrite the sanitised value
 * with raw remote text — making the hardening this class centralises optional at
 * the far end's discretion.
 *
 * <p><b>The body.</b> A hit may carry the source's own text — an OpenAlex or
 * arXiv abstract, a Wikipedia extract — in
 * {@link ContentReference#inlineText()}. Those were already being fetched and
 * then dropped here, because nothing read {@code hit.content()}: the whole
 * content channel had no consumer anywhere in the tree. It is surfaced as
 * {@code body}, capped at {@link #MAX_BODY_CHARS}.
 *
 * <p>The cap is a cost decision, not a technical one. An abstract runs 300–500
 * tokens and ten academic hits would add 3–5k to a single search; a thousand
 * characters is enough to judge whether a paper is the right one, which is what
 * a search result is for. Anything beyond that is a reading task, and reading is
 * what the URL and — where a provider implements it — {@code loadContent} are
 * for.
 */
final class SearchHitRows {

    /**
     * Characters of source text carried per hit. See the class comment: this
     * bounds what a search costs, it is not a property of any source.
     */
    static final int MAX_BODY_CHARS = 1000;

    /**
     * A truncated body ends in this. The model must be able to tell a cut-off
     * sentence from a complete one, or it will quote half of one as the whole
     * thing.
     */
    static final String ELLIPSIS = "…";

    /** How far back to look for a word boundary rather than cutting mid-word. */
    private static final int WORD_BOUNDARY_SLACK = 40;

    private SearchHitRows() {
        /* static only */
    }

    /**
     * Shape one hit. Untrusted fields are whitespace-collapsed so they cannot
     * inject structure when an engine renders the row into a templated prompt.
     */
    static Map<String, Object> shape(SearchHit hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        // Extras first, and the canonical fields after them.
        //
        // Inlined rather than wrapped in a sub-map, so the LLM sees them as
        // first-class fields per modality (imageUrl, doi, citedByCount, …) —
        // but a provider fills this map, so it must not be able to land on a
        // key this class is responsible for. Written last, the canonical fields
        // win: otherwise an `extras.title` would replace the collapsed title
        // with raw remote text, and the one hardening this class exists to
        // centralise would be optional at the source's discretion.
        if (hit.extras() != null && !hit.extras().isEmpty()) {
            for (Map.Entry<String, Object> e : hit.extras().entrySet()) {
                row.put(e.getKey(), safeExtra(e.getValue()));
            }
        }
        row.put("title", UntrustedContent.collapseWhitespace(hit.title()));
        row.put("url", hit.url());
        if (!StringUtils.isBlank(hit.snippet())) {
            row.put("snippet", UntrustedContent.collapseWhitespace(hit.snippet()));
        }
        if (!StringUtils.isBlank(hit.source())) {
            row.put("source", UntrustedContent.collapseWhitespace(hit.source()));
        }
        String body = bodyOf(hit.content());
        if (body != null) {
            row.put("body", body);
        }
        return row;
    }

    /**
     * An extra value on its way into a prompt. Numbers and booleans pass
     * through; anything textual is collapsed like every other remote string —
     * extras are as foreign as the title beside them, and an unsanitised one is
     * a hole in exactly the wall the sibling fields stand behind.
     */
    private static Object safeExtra(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return UntrustedContent.collapseWhitespace(String.valueOf(value));
    }

    /**
     * The inline body of a hit, or null when there is none to show.
     *
     * <p>{@link ContentInline#STASH_ON_DEMAND} yields null here even though the
     * reference is present: those bytes need a {@code loadContent} call, and no
     * caller in the brain makes one yet. Returning the empty
     * {@code inlineText} of a stashed reference would put an empty field on
     * every hit of such a source.
     */
    private static @Nullable String bodyOf(@Nullable ContentReference content) {
        if (content == null || content.inline() != ContentInline.EMBED_TEXT) {
            return null;
        }
        String text = content.inlineText();
        if (StringUtils.isBlank(text)) {
            return null;
        }
        return truncate(UntrustedContent.collapseWhitespace(text));
    }

    /**
     * Cap at {@link #MAX_BODY_CHARS}, preferring the last word boundary within
     * {@link #WORD_BOUNDARY_SLACK} characters of the limit — a cut mid-word
     * reads like a broken value rather than an abbreviated one.
     */
    private static String truncate(String text) {
        if (text.length() <= MAX_BODY_CHARS) {
            return text;
        }
        String head = text.substring(0, MAX_BODY_CHARS);
        int lastSpace = head.lastIndexOf(' ');
        if (lastSpace >= MAX_BODY_CHARS - WORD_BOUNDARY_SLACK) {
            head = head.substring(0, lastSpace);
        }
        return head.stripTrailing() + ELLIPSIS;
    }
}
