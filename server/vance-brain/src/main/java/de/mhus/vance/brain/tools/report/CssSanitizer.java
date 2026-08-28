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
 * than {@code data:}, {@code javascript:} URIs, {@code expression(...)},
 * {@code behavior:}, {@code -moz-binding:}. Each removal logs a WARN so
 * the operator can see which theme tripped the filter.
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
        String result = css;

        // @import — drop entirely (with the trailing semicolon).
        Matcher importMatcher = IMPORT.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (importMatcher.find()) {
            warnings.add("@import: " + importMatcher.group().trim());
        }
        result = importMatcher.replaceAll("");
        if (result.indexOf('@') >= 0) {
            // A second pass is not needed: IMPORT is greedy up to ';', so
            // a multi-statement @import chain is one match. Leftover '@'
            // chars are @media/@page/@font-face, which we keep.
        }

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
