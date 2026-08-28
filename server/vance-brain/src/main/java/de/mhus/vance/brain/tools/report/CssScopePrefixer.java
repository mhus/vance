package de.mhus.vance.brain.tools.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Prefixes every selector in a CSS string with a fixed scope class so a
 * theme served to the web-UI preview cannot leak onto the rest of the
 * page. {@code h1 { color: red }} becomes
 * {@code .markdown-document-preview h1 { color: red }}; a theme that
 * accidentally writes {@code body { background: red }} becomes
 * {@code .markdown-document-preview body { … }} which never matches
 * (there is no {@code body} below the component).
 *
 * <p>The scoping is the web-UI equivalent of the PDF path's encapsulation:
 * the PDF is its own document, so the theme applies globally inside it;
 * the web preview lives inside the Cortex shell, so the theme must be
 * fenced to the preview root. Shadow DOM would have given a real DOM
 * boundary but breaks the Vue VNodes that render embedded kinds (see
 * {@code report-themes.md} §9.3) — scoping the CSS server-side is the
 * compromise that keeps the kinds working.
 *
 * <p>The implementation works in two passes:
 * <ol>
 *   <li><b>Lift</b> every at-rule block whose body must not be prefixed
 *       ({@code @page}, {@code @font-face}, {@code @keyframes}) out into
 *       a side list, replacing each with a placeholder.</li>
 *   <li><b>Walk</b> the remaining CSS top-level by top-level braces
 *       (depth-counted, so {@code @media}'s inner rules are handled
 *       correctly). For each top-level rule, prefix the selector list;
 *       for each top-level {@code @media}/{@code @supports} block,
 *       recurse into the inner content and prefix the inner rules.</li>
 * </ol>
 *
 * <p><b>Limitation.</b> CSS Nesting (the {@code &} combinator, native
 * nested rules) is not supported — a nested block would be prefixed as if
 * flat, which produces wrong selectors. Themes are expected to be flat
 * CSS (the bundled {@code default.css} / {@code acme.css} are). An author
 * who writes nesting gets correct results in the PDF (openhtmltopdf
 * ignores it the same way) and a broken preview — a documented
 * compromise, not a bug.
 */
public final class CssScopePrefixer {

    /** The scope class every selector is prefixed with. */
    public static final String SCOPE = ".markdown-document-preview";

    /** At-rules whose body must be left untouched (selector-less / frame selectors). */
    private static final Pattern PRESERVE_AT_RULE_START = Pattern.compile(
        "@(?:page|font-face|keyframes)\\s*[^{]*\\{",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private CssScopePrefixer() {
        // utility class
    }

    /**
     * Returns the input CSS with every element-selecting rule scoped
     * under {@link #SCOPE}. At-rules that do not select elements
     * ({@code @page}, {@code @font-face}, {@code @keyframes}) are
     * preserved verbatim. {@code null} input becomes an empty string.
     *
     * @param css already-filtered theme CSS (see {@link CssSanitizer}),
     *            may be {@code null}
     * @return scoped CSS, never {@code null}
     */
    public static String scope(@Nullable String css) {
        if (css == null || css.isEmpty()) return "";
        String work = css;

        // Pass 1: lift @page / @font-face / @keyframes blocks out so the
        // rule walker does not prefix their bodies. These at-rules can
        // nest braces (e.g. @page { @bottom-right { … } }, @keyframes
        // { from { … } to { … } }), so we match the start and then walk
        // braces to find the matching close — a regex on [^{}] only
        // handles one level.
        List<String> preserves = new ArrayList<>();
        StringBuilder lifted = new StringBuilder();
        Matcher pm = PRESERVE_AT_RULE_START.matcher(work);
        int last = 0;
        while (pm.find()) {
            lifted.append(work, last, pm.start());
            int openBrace = pm.end() - 1; // position of the '{'
            int depth = 1;
            int k = openBrace + 1;
            while (k < work.length() && depth > 0) {
                char c = work.charAt(k);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                if (depth == 0) break;
                k++;
            }
            if (k >= work.length()) {
                // Unbalanced — leave the matched fragment as-is.
                lifted.append(pm.group());
                last = pm.end();
                continue;
            }
            String whole = work.substring(pm.start(), k + 1);
            preserves.add(whole);
            lifted.append("@@PRESERVE").append(preserves.size() - 1).append("@@");
            last = k + 1;
        }
        lifted.append(work, last, work.length());
        work = lifted.toString();

        // Pass 2: walk top-level braces. The walker sees three kinds of
        // top-level constructs: conditional at-rules (@media/@supports,
        // whose opening brace starts a block we recurse into), plain
        // rules (selector-list + body), and stray text (whitespace,
        // comments) which is passed through.
        work = walkTopLevel(work);

        // Restore preserves. The walker leaves placeholders verbatim
        // (they are never prefixed), so a plain text substitution is
        // enough.
        for (int i = 0; i < preserves.size(); i++) {
            work = work.replace("@@PRESERVE" + i + "@@", preserves.get(i));
        }
        return work;
    }

    /**
     * Walk the CSS one top-level brace pair at a time. For each rule we
     * find its selector-list (text before the opening brace) and prefix
     * it. When the selector-list begins with {@code @media}/
     * {@code @supports}, the block is a wrapper whose inner content is
     * itself a list of rules — we recurse by re-walking the inner text.
     */
    private static String walkTopLevel(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        int n = css.length();
        while (i < n) {
            int open = css.indexOf('{', i);
            if (open < 0) {
                // No more braces — trailing text passes through.
                out.append(css, i, n);
                break;
            }
            // Text before the opening brace is the "selector list" (or
            // an at-rule header). Pass it through for now; we decide
            // below whether to prefix it.
            String head = css.substring(i, open);
            // Find the matching close brace (depth-counted).
            int depth = 1;
            int j = open + 1;
            while (j < n && depth > 0) {
                char c = css.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                if (depth == 0) break;
                j++;
            }
            if (j >= n) {
                // Unbalanced — pass the rest through verbatim (defensive).
                out.append(css, i, n);
                break;
            }
            String body = css.substring(open + 1, j);
            String trimmedHead = head.trim();

            if (trimmedHead.regionMatches(true, 0, "@media", 0, 6)
                || trimmedHead.regionMatches(true, 0, "@supports", 0, 9)) {
                // Conditional at-rule: keep the header, recurse into the body.
                out.append(head);
                out.append('{');
                out.append(walkTopLevel(body));
                out.append('}');
            } else if (trimmedHead.isEmpty()) {
                // No selector (stray block) — pass through unchanged.
                out.append(head);
                out.append('{');
                out.append(body);
                out.append('}');
            } else {
                // Plain rule: prefix the selector list. A preserve
                // placeholder that landed inside the head (a @page /
                // @font-face / @keyframes block sitting between two rules)
                // must not be prefixed — split the head at the first
                // placeholder, leave everything up to and including it
                // verbatim, and prefix only the remainder.
                int ph = head.indexOf("@@PRESERVE");
                if (ph >= 0) {
                    String before = head.substring(0, ph);
                    String rest = head.substring(ph);
                    // The placeholder token ends with "@@"; the real
                    // selector list is everything after it.
                    int tokEnd = rest.indexOf("@@", 2);
                    if (tokEnd >= 0) {
                        String token = rest.substring(0, tokEnd + 2);
                        String after = rest.substring(tokEnd + 2);
                        out.append(before);
                        out.append(token);
                        out.append(prefixSelectorList(after));
                        out.append(" {");
                        out.append(body);
                        out.append('}');
                    } else {
                        out.append(prefixSelectorList(head));
                        out.append(" {");
                        out.append(body);
                        out.append('}');
                    }
                } else {
                    out.append(prefixSelectorList(head));
                    out.append(" {");
                    out.append(body);
                    out.append('}');
                }
            }
            i = j + 1;
        }
        return out.toString();
    }

    private static String prefixSelectorList(String selectors) {
        // Preserve leading whitespace (newlines, indentation inside an
        // @media block) so a rule keeps its layout after the prefix is
        // inserted. Trailing whitespace is dropped — the caller appends
        // " {" and we don't want a double space.
        String leading = "";
        int firstNonWs = 0;
        while (firstNonWs < selectors.length()
            && Character.isWhitespace(selectors.charAt(firstNonWs))) firstNonWs++;
        if (firstNonWs > 0) leading = selectors.substring(0, firstNonWs);
        String trimmed = selectors.substring(firstNonWs).trim();
        if (trimmed.isEmpty()) return selectors;
        // Strip CSS comments before prefixing. A comment before a
        // selector (e.g. "/* note */ h1 { … }") would otherwise land
        // between the scope prefix and the element (".scope /* note */
        // h1"), which breaks the cascade — the prefix no longer applies
        // to h1. Comments inside declarations are left untouched (they
        // are in the body, not the head). Only comments in the selector
        // list itself are stripped here.
        String noComments = stripCssComments(trimmed);
        // Split on top-level commas only (commas inside :is(), :where(),
        // nth-child(...) are parenthesised and should not split).
        StringBuilder result = new StringBuilder();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < noComments.length(); i++) {
            char c = noComments.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) {
                result.append(prefixOne(noComments.substring(start, i).trim()));
                result.append(", ");
                start = i + 1;
            }
        }
        result.append(prefixOne(noComments.substring(start).trim()));
        return leading + result;
    }

    /** Strips {@code /* … *​/} comments from a CSS fragment. Used on the
     * selector-list head of a rule so a leading comment does not land
     * between the scope prefix and the first element. */
    private static String stripCssComments(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        while (i < css.length()) {
            if (i + 1 < css.length() && css.charAt(i) == '/' && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                if (end < 0) break; // unterminated — drop the rest
                i = end + 2;
                // Collapse a comment to a single space so "/*x*/h1" does
                // not become "h1" (which would be a different selector if
                // it had been ".x h1" before). A space between the
                // (now removed) comment and the selector is harmless.
                out.append(' ');
            } else {
                out.append(css.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String prefixOne(String selector) {
        if (selector.isEmpty()) return selector;
        // The scope class is doubled (.markdown-document-preview twice)
        // so the rule's specificity matches a Vue scoped style like
        // `.markdown-view[data-v-xxx] a` (one class + one attribute = 0,2,0).
        // A single .markdown-document-preview would lose the cascade
        // to MarkdownView's scoped styles and the theme would not apply
        // to links, code backgrounds, etc. Doubling gives 0,2,0 too, and
        // source order then decides — the theme <style> comes after the
        // scoped styles in the DOM, so the theme wins ties.
        String scope = SCOPE + SCOPE;
        // One form covers both cases: a leading combinator (`> h1`) reads as
        // "direct child of the scope root" once the class is on its left,
        // which is exactly the same concatenation a plain selector needs.
        return scope + " " + selector;
    }
}
