package de.mhus.vance.shared.document.jaglan;

import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * The reader's query for a parameterised mount read, separated from the
 * parameters that belong to Vancetope.
 *
 * <p>Two namespaces share one query string. {@code vance:} references already
 * use it for {@code kind=}, and the content endpoint uses it for
 * {@code download=} — so a mount that is handed the query verbatim would
 * receive words that were never meant for it, and worse, a mount parameter
 * called {@code kind} would be read by us instead of by the source. One
 * reserved list, applied wherever a query is forwarded, keeps that from being
 * decided independently at each surface.
 *
 * <p>The query is passed on <b>as written</b>, only filtered. It is already
 * percent-encoded — it is the string the reader produced — and re-encoding it
 * would collapse {@code a=1&b=2} into a single opaque parameter.
 *
 * <p>Reserved names are <b>dropped, not refused</b>. They are ours and always
 * legitimately present (a download link carries {@code download=true}); a
 * refusal would break the ordinary case. The mirror image is the {@code path}
 * parameter of the {@code ode} wire, which a reader query may <b>not</b>
 * declare — that one is refused, because a shadowed path reads a different
 * file than the one addressed.
 */
public final class MountQuery {

    /**
     * Query parameters that belong to Vancetope and never travel to a source.
     *
     * <p>{@code kind} is the word the reference grammar uses to say how a
     * document should be interpreted; {@code download} is the content
     * endpoint's disposition switch. A source needing a parameter of either
     * name has to pick another — a collision here is not resolvable in the
     * source's favour without us losing the meaning we depend on.
     */
    public static final Set<String> RESERVED = Set.of("kind", "download");

    private MountQuery() {}

    /**
     * The forwardable part of {@code rawQuery}, or {@code null} when nothing is
     * left over.
     *
     * <p>Null rather than an empty string on purpose: everything downstream
     * treats null as "a plain read", and an empty string would take the
     * parameterised branch for a query with no parameters in it.
     *
     * @param rawQuery a query string without the leading {@code ?}, or null
     */
    public static @Nullable String forward(@Nullable String rawQuery) {
        if (StringUtils.isBlank(rawQuery)) {
            return null;
        }
        StringBuilder kept = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            if (RESERVED.contains(keyOf(pair))) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('&');
            }
            kept.append(pair);
        }
        return kept.isEmpty() ? null : kept.toString();
    }

    /** Whether {@code rawQuery} carries anything a source would receive. */
    public static boolean hasForwardable(@Nullable String rawQuery) {
        return forward(rawQuery) != null;
    }

    /** The parameter name of one {@code key=value} pair, lowercased. */
    private static String keyOf(String pair) {
        int eq = pair.indexOf('=');
        String key = eq < 0 ? pair : pair.substring(0, eq);
        return key.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
