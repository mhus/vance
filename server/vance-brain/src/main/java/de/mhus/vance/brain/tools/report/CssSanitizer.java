package de.mhus.vance.brain.tools.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strips dangerous constructs from a theme CSS string before it is served
 * to the web-UI preview. The PDF path embeds the CSS as a string into the
 * openhtmltopdf {@code <style>} block, where {@code url()} resolves against
 * a {@code null} base URI and is skipped — no resource is ever fetched. The
 * browser is a real resource loader: a {@code url('https://evil/x.png')}
 * in a theme would fire an actual request (Exfil / SSRF), and an
 * {@code @import 'https://evil'} would pull a remote stylesheet. This
 * sanitizer removes those constructs so a semi-trusted theme document can
 * be served to the client without becoming an attack vector.
 *
 * <p>The input is CSS, not HTML — the {@code <script>} vector does not
 * exist (CSS does not create elements). The attack surface is small but
 * real: {@code @import}, {@code url()} with external schemes,
 * {@code javascript:} URIs, and the IE-era expression vectors
 * ({@code expression(...)}, {@code behavior}, {@code -moz-binding}). All
 * are removed.
 *
 * <p>The implementation is a simple token scanner, not a full CSS parser.
 * CSS is tolerant enough that {@code "find @import and drop the line"} and
 * {@code "find url(...) and check the scheme"} are sufficient. The
 * consequence of an incomplete filter is a leftover {@code @import}
 * (a coarse syntax error, breaks nothing globally) or a leftover
 * {@code url('https://…')} (a request that fires — the real risk, which
 * is why the {@code url()} scan is the most important one).
 *
 * <p>Allowed after filtering: all standard properties, all standard
 * selectors (selectors are not filtered here — they are scoped separately
 * by {@link CssScopePrefixer}), {@code @media}, {@code @page},
 * {@code @font-face} with {@code data:} sources only.
 *
 * <p>Stripped: {@code @import} (any), {@code url()} with schemes other
 * than {@code data:}, the other image-producing functions that take a bare
 * URL string and therefore fetch without a {@code url()} token in sight
 * ({@code image-set()}, {@code cross-fade()}, {@code element()}, and their
 * {@code -webkit-} spellings), {@code javascript:} URIs,
 * {@code expression(...)}, {@code behavior:}, {@code -moz-binding:}. Each
 * removal logs a WARN so the operator can see which theme tripped the
 * filter.
 *
 * <p><b>Two normalisations run first, and they are what make the scan
 * honest.</b> A token scanner is only as good as its idea of where a token
 * starts, and CSS offers two cheap ways to disagree with it:
 *
 * <ul>
 *   <li><b>Comments.</b> {@code @import/*x*}{@code /"evil";} is valid CSS
 *       and does not match a pattern anchored on {@code @import\s}. Comments
 *       carry no meaning to a browser, so they are removed outright.</li>
 *   <li><b>Identifier escapes.</b> {@code \75 rl("https://evil")} is the
 *       {@code url()} function written the long way, and
 *       {@code @\69 mport} is {@code @import}. Every backslash <em>outside
 *       a quoted string</em> is dropped, which turns those back into the
 *       plain text the patterns look for. Escapes inside strings — the only
 *       place a theme legitimately needs one, e.g. {@code content: "\201C"}
 *       — are left alone.</li>
 * </ul>
 */
public final class CssSanitizer {

    private static final Logger LOG = LoggerFactory.getLogger(CssSanitizer.class);

    /** {@code @import} at-rule, with or without quotes, with or without media query. */
    private static final Pattern IMPORT =
        Pattern.compile("@import\\s+[^;]+;", Pattern.CASE_INSENSITIVE);

    /** Every {@code url(...)} occurrence — the scheme is checked per-match. */
    private static final Pattern URL_FUNC =
        Pattern.compile("url\\(\\s*([^)]*?)\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** {@code expression(...)} — IE dynamic-expression vector. */
    private static final Pattern EXPRESSION =
        Pattern.compile("expression\\s*\\([^)]*\\)", Pattern.CASE_INSENSITIVE);

    /** {@code behavior: ...;} and {@code -moz-binding: ...;} — IE / old-Firefox binding vector. */
    private static final Pattern BEHAVIOR =
        Pattern.compile("(?:behavior|-moz-binding)\\s*:[^;]*;", Pattern.CASE_INSENSITIVE);

    /** {@code javascript:} URI anywhere — in url() or as a bare attribute value. */
    private static final Pattern JS_URI =
        Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE);

    /**
     * The image functions that accept a bare URL <em>string</em>, so they
     * fetch with no {@code url()} token to find:
     * {@code background-image: image-set("https://evil/x.png" 1x)}. Replaced
     * whole with {@code none} rather than emptied — an {@code image-set()}
     * with no argument is a parse error that takes its declaration with it,
     * while {@code none} is a valid image value.
     *
     * <p>{@code element()} joins them for the same shape: it takes a
     * reference, not a colour, and a filter that keeps it would have to
     * reason about what it points at.
     */
    private static final Pattern IMAGE_FUNC = Pattern.compile(
        "(?:-webkit-|-moz-|-o-)?(?:image-set|cross-fade|element)\\s*"
            // One level of nesting, because the realistic spelling wraps
            // url(): image-set(url("a") 1x, url("b") 2x). A flat [^)]* would
            // stop at the first inner ')' and leave the tail behind.
            + "\\((?:[^()]|\\([^()]*\\))*\\)",
        Pattern.CASE_INSENSITIVE);

    private CssSanitizer() {
        // utility class
    }

    /**
     * Returns a filtered copy of the input CSS. Never throws — a parse
     * problem degrades to dropping the offending construct, not to
     * failing the render. {@code null} input becomes an empty string.
     *
     * @param css raw theme CSS, may be {@code null}
     * @return filtered CSS, safe to send to a browser; never {@code null}
     */
    public static String sanitize(@Nullable String css) {
        if (css == null || css.isEmpty()) return "";
        List<String> warnings = new ArrayList<>();

        // Normalise before scanning — see the class comment. Both passes only
        // ever remove characters a browser does not need, so a theme that was
        // not trying to hide anything comes through unchanged.
        String result = dropEscapesOutsideStrings(stripComments(css));

        // @import — drop entirely (with the trailing semicolon). No second
        // pass needed: IMPORT is greedy up to ';', so a multi-statement
        // @import chain is one match. Leftover '@' chars are
        // @media/@page/@font-face, which we keep.
        Matcher importMatcher = IMPORT.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (importMatcher.find()) {
            warnings.add("@import: " + importMatcher.group().trim());
        }
        result = importMatcher.replaceAll("");

        // image-set() / cross-fade() / element() — dropped whole, before the
        // url() pass, because they nest it: emptying the inner url() first
        // would leave a half-eaten function for this pattern to match.
        Matcher imageMatcher = IMAGE_FUNC.matcher(result);
        while (imageMatcher.find()) {
            warnings.add(imageMatcher.group().trim());
        }
        result = imageMatcher.replaceAll("none");

        // url(...) — keep only data: URLs. Any other scheme (http, https,
        // file, jar, ftp, relative, absolute without scheme) is removed.
        // A removed url() leaves an empty argument list, which CSS treats
        // as a no-op (e.g. `background: url(...) red` → `background:  red`,
        // still valid, the fallback color wins).
        Matcher urlMatcher = URL_FUNC.matcher(result);
        sb.setLength(0);
        while (urlMatcher.find()) {
            String inside = urlMatcher.group(1).trim();
            String unquoted = stripQuotes(inside);
            if (!isDataUrl(unquoted)) {
                warnings.add("url(" + inside + ")");
                urlMatcher.appendReplacement(sb, "url()");
            } else {
                urlMatcher.appendReplacement(sb, Matcher.quoteReplacement(urlMatcher.group()));
            }
        }
        urlMatcher.appendTail(sb);
        result = sb.toString();

        // expression(...) — replace with empty.
        Matcher exprMatcher = EXPRESSION.matcher(result);
        while (exprMatcher.find()) {
            warnings.add("expression(...)");
        }
        result = exprMatcher.replaceAll("");

        // behavior / -moz-binding — drop the declaration.
        Matcher behaviorMatcher = BEHAVIOR.matcher(result);
        while (behaviorMatcher.find()) {
            warnings.add(behaviorMatcher.group().trim());
        }
        result = behaviorMatcher.replaceAll("");

        // javascript: URIs — drop the scheme prefix.
        Matcher jsMatcher = JS_URI.matcher(result);
        while (jsMatcher.find()) {
            warnings.add("javascript: URI");
        }
        result = jsMatcher.replaceAll("");

        if (!warnings.isEmpty()) {
            LOG.warn("CssSanitizer removed {} construct(s) from theme CSS: {}",
                warnings.size(), warnings);
        }
        return result;
    }

    /**
     * Removes {@code /* … *}{@code /} comments. A comment may sit between an
     * at-keyword and its argument ({@code @import/*x*}{@code /"evil";}),
     * which is valid CSS and invisible to every pattern above. Browsers get
     * nothing from comments, so dropping them costs nothing and closes the
     * whole class.
     *
     * <p>An unterminated comment takes the rest of the input with it — that
     * is also what a browser does with it.
     */
    private static String stripComments(String css) {
        int start = css.indexOf("/*");
        if (start < 0) return css;
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        while (i < css.length()) {
            if (i + 1 < css.length() && css.charAt(i) == '/' && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 2;
                // A space so "background/*x*/:red" does not become a single
                // token that means something else.
                out.append(' ');
            } else {
                out.append(css.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Drops every backslash that is not inside a quoted string.
     *
     * <p>CSS lets an identifier be written with escapes, so {@code url(} can
     * be spelled {@code \75 rl(} and {@code @import} as {@code @\69 mport} —
     * both parse to the real thing and neither matches a pattern looking for
     * the literal word. Removing the backslash leaves {@code 75 rl(}, which
     * is not a function token at all.
     *
     * <p>Inside a string an escape is ordinary and meaningful
     * ({@code content: "\201C"}), and a string cannot become a function name,
     * so strings are stepped over untouched — including their own
     * {@code \"} escapes, which is why the quote state has to track them.
     *
     * <p>The cost is a selector that legitimately escapes a character
     * ({@code .w-1\/2}, the Tailwind spelling) — it stops matching. Themes
     * here are hand-written flat CSS and do not have those; the same
     * selector is already outside what {@link CssScopePrefixer} handles.
     */
    private static String dropEscapesOutsideStrings(String css) {
        if (css.indexOf('\\') < 0) return css;
        StringBuilder out = new StringBuilder(css.length());
        char quote = 0;
        for (int i = 0; i < css.length(); i++) {
            char c = css.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < css.length()) {
                    out.append(css.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                out.append(c);
            } else if (c != '\\') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static boolean isDataUrl(String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.regionMatches(true, 0, "data:", 0, 5);
    }
}
