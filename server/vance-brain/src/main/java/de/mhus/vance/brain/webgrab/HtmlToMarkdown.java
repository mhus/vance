package de.mhus.vance.brain.webgrab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * Turns a web page into Markdown that is worth keeping.
 *
 * <p><b>Why this exists next to {@code WebFetchTool.htmlToText}.</b> That one
 * produces plain text — jsoup's {@code .text()} with newline markers. It is the
 * right shape for a prompt, where structure costs tokens and buys little. It is
 * the wrong shape for a document somebody opens in a month: headings, lists,
 * links and code blocks are exactly what makes a saved article readable, and
 * flattening them is not recoverable.
 *
 * <p><b>The content heuristic is a heuristic, and says so.</b> A real page is
 * mostly not the article: navigation, cookie banners, related-articles rails,
 * footers. Proper extraction is Readability, and porting Readability to Java is
 * out of proportion to a grab endpoint. What happens instead: prefer
 * {@code <article>} / {@code <main>} / {@code [role=main]} — and among several,
 * the one with the most text — else fall back to {@code <body>}; then drop the
 * tags that are chrome by definition. That gets most pages right and fails
 * visibly (too much kept) rather than invisibly (the article silently dropped).
 *
 * <p>Everything here is pure: HTML in, Markdown out, no IO. The caller supplies
 * the source URL as the base so relative links and images come out absolute —
 * a grabbed page whose links all point at {@code /about} is a page you cannot
 * follow.
 */
public final class HtmlToMarkdown {

    /**
     * Ceiling on the HTML we will parse. A page that large is a database dump
     * or a bug, and jsoup builds a full DOM before anyone can look at it.
     */
    public static final int MAX_HTML_CHARS = 4_000_000;

    /** Ceiling on the Markdown we produce. */
    public static final int MAX_MARKDOWN_CHARS = 1_000_000;

    /**
     * Tags that are page chrome wherever they appear inside the chosen root.
     * {@code header} is deliberately absent — inside an {@code <article>} it is
     * usually the title block, which is the opposite of chrome. It is dropped
     * only when the root fell back to {@code <body>}; see {@link #strip}.
     */
    private static final String CHROME =
            "script, style, noscript, template, nav, aside, footer, form, iframe, svg, "
            + "button, input, select, textarea, dialog, [aria-hidden=true], [hidden]";

    /** Candidates for "the actual content", best first. */
    private static final String CONTENT_ROOTS = "article, main, [role=main]";

    private HtmlToMarkdown() {}

    /**
     * The page, converted.
     *
     * @param title    the page's own title — {@code og:title}, else
     *                 {@code <title>}, else the first heading. May be blank
     *                 when the page offers none; the caller decides what to
     *                 name the document then.
     * @param markdown the body.
     */
    public record Result(String title, String markdown) {}

    /**
     * @param html    the page source — for a browser extension, the *rendered*
     *                DOM, which is the whole reason it is sent instead of a URL.
     * @param baseUri the page's URL, used to make links and images absolute.
     */
    public static Result convert(String html, String baseUri) {
        String source = html == null ? "" : html;
        if (source.length() > MAX_HTML_CHARS) {
            source = source.substring(0, MAX_HTML_CHARS);
        }
        org.jsoup.nodes.Document doc = Jsoup.parse(source, baseUri == null ? "" : baseUri);
        String title = title(doc);

        Element root = contentRoot(doc);
        strip(root, root == doc.body());

        StringBuilder out = new StringBuilder();
        for (Node child : root.childNodes()) {
            append(child, out, 0, false);
        }
        return new Result(title, tidy(out.toString()));
    }

    // ── choosing what to convert ──────────────────────────────────

    /**
     * The largest {@code article}/{@code main}, or the body.
     *
     * <p>Largest rather than first, because a listing page is a stack of
     * {@code <article>} teasers and the first one is a headline with two
     * sentences. On a normal article page there is only one candidate and the
     * choice is free.
     */
    private static Element contentRoot(org.jsoup.nodes.Document doc) {
        Elements candidates = doc.select(CONTENT_ROOTS);
        Element best = null;
        int bestLength = 0;
        for (Element candidate : candidates) {
            int length = candidate.text().length();
            if (length > bestLength) {
                best = candidate;
                bestLength = length;
            }
        }
        Element body = doc.body();
        if (best == null) return body;
        // A "main" holding almost nothing is a landmark on a page whose content
        // sits elsewhere. Trusting it would produce an empty document from a
        // page that plainly has text.
        return bestLength < 200 && body != null && body.text().length() > bestLength * 2
                ? body
                : best;
    }

    private static void strip(Element root, boolean rootIsBody) {
        root.select(CHROME).remove();
        if (rootIsBody) {
            // No article landmark was found, so this is the whole page and a
            // <header> is the site header, not a title block.
            root.select("header").remove();
        }
    }

    // ── the walk ──────────────────────────────────────────────────

    private static void append(Node node, StringBuilder out, int depth, boolean inPre) {
        if (node instanceof TextNode text) {
            String value = inPre ? text.getWholeText() : text.text();
            out.append(inPre ? value : escape(value));
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }
        switch (el.tagName().toLowerCase(Locale.ROOT)) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = el.tagName().charAt(1) - '0';
                String text = inline(el).trim();
                if (!text.isEmpty()) {
                    block(out);
                    out.append("#".repeat(level)).append(' ').append(text);
                    block(out);
                }
            }
            case "p" -> {
                String text = inline(el).trim();
                if (!text.isEmpty()) {
                    block(out);
                    out.append(text);
                    block(out);
                }
            }
            case "br" -> out.append('\n');
            case "hr" -> {
                block(out);
                out.append("---");
                block(out);
            }
            case "strong", "b" -> wrap(el, out, "**");
            case "em", "i" -> wrap(el, out, "*");
            case "del", "s" -> wrap(el, out, "~~");
            case "code" -> {
                if (inPre) {
                    appendChildren(el, out, depth, true);
                } else {
                    // Backticks inside inline code need a longer fence; one
                    // level covers everything short of deliberate abuse.
                    String text = el.text();
                    out.append(text.contains("`") ? "`` " + text + " ``" : "`" + text + "`");
                }
            }
            case "pre" -> {
                block(out);
                out.append("```").append(language(el)).append('\n');
                String body = el.wholeText();
                out.append(body);
                if (!body.endsWith("\n")) out.append('\n');
                out.append("```");
                block(out);
            }
            case "a" -> {
                String text = inline(el).trim();
                String href = absolute(el, "href");
                if (text.isEmpty()) return;
                if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")) {
                    // An anchor with nowhere to go is text: no href, a script
                    // handler, or a bare fragment we could not resolve.
                    //
                    // Note what this does *not* catch — a fragment we could
                    // resolve. With a source URL, `#footnote-1` becomes a link
                    // into the original page's footnote, which is exactly what
                    // the reader of a saved copy wants. The check runs on the
                    // resolved value on purpose, so "has a base" is what
                    // decides.
                    out.append(text);
                } else {
                    out.append('[').append(text).append("](").append(href).append(')');
                }
            }
            case "img" -> {
                String src = absolute(el, "src");
                if (!src.isEmpty()) {
                    out.append("![").append(escape(el.attr("alt"))).append("](")
                            .append(src).append(')');
                }
            }
            case "ul", "ol" -> {
                block(out);
                list(el, out, depth);
                block(out);
            }
            case "blockquote" -> {
                block(out);
                out.append(prefixLines(tidy(childrenOf(el, depth)), "> "));
                block(out);
            }
            case "table" -> {
                String table = table(el);
                if (table.isEmpty()) {
                    // Not rectangular enough to be a table — a layout table, or
                    // one with spans. Its cells are still text worth keeping.
                    appendChildren(el, out, depth, false);
                } else {
                    block(out);
                    out.append(table);
                    block(out);
                }
            }
            case "div", "section", "article", "main", "figure", "figcaption",
                 "header", "li", "dd", "dt", "dl", "tbody", "thead", "tr", "td", "th" -> {
                block(out);
                appendChildren(el, out, depth, inPre);
                block(out);
            }
            default -> appendChildren(el, out, depth, inPre);
        }
    }

    private static void appendChildren(Element el, StringBuilder out, int depth, boolean inPre) {
        for (Node child : el.childNodes()) {
            append(child, out, depth, inPre);
        }
    }

    private static void wrap(Element el, StringBuilder out, String marker) {
        String text = inline(el).trim();
        if (!text.isEmpty()) {
            out.append(marker).append(text).append(marker);
        }
    }

    /** Render an element's children and flatten to one line. */
    private static String inline(Element el) {
        StringBuilder buffer = new StringBuilder();
        appendChildren(el, buffer, 0, false);
        return buffer.toString().replaceAll("\\s*\\n\\s*", " ").replaceAll(" {2,}", " ").trim();
    }

    private static String childrenOf(Element el, int depth) {
        StringBuilder buffer = new StringBuilder();
        appendChildren(el, buffer, depth, false);
        return buffer.toString();
    }

    private static void list(Element el, StringBuilder out, int depth) {
        boolean ordered = "ol".equalsIgnoreCase(el.tagName());
        String indent = "  ".repeat(depth);
        int index = 1;
        for (Element item : el.children()) {
            if (!"li".equalsIgnoreCase(item.tagName())) continue;
            String marker = ordered ? (index++) + ". " : "- ";
            // Nested lists are rendered by the recursion below and must not be
            // pulled into the item's own line, so they are handled separately
            // from the item's inline content.
            Elements nested = new Elements();
            for (Element child : item.children()) {
                if ("ul".equalsIgnoreCase(child.tagName())
                        || "ol".equalsIgnoreCase(child.tagName())) {
                    nested.add(child);
                }
            }
            nested.forEach(Node::remove);

            String text = inline(item);
            out.append(indent).append(marker).append(text).append('\n');
            for (Element sub : nested) {
                list(sub, out, depth + 1);
            }
        }
    }

    /**
     * A GFM table, or empty when the element is not one.
     *
     * <p>Empty rather than a best effort, because a layout table rendered as a
     * grid is worse than the same cells as paragraphs — the reader gets a
     * one-column table where the page had a sidebar.
     */
    private static String table(Element el) {
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : el.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.children()) {
                String tag = cell.tagName().toLowerCase(Locale.ROOT);
                if (!tag.equals("td") && !tag.equals("th")) continue;
                // A pipe or a newline inside a cell ends the row early — the
                // one way a table can corrupt the document around it.
                cells.add(inline(cell).replace("|", "\\|"));
            }
            if (!cells.isEmpty()) rows.add(cells);
        }
        if (rows.size() < 2) return "";
        int width = rows.getFirst().size();
        if (width < 2) return "";
        for (List<String> row : rows) {
            if (row.size() != width) return "";   // spans — not a grid
        }

        StringBuilder out = new StringBuilder();
        out.append("| ").append(String.join(" | ", rows.getFirst())).append(" |\n");
        out.append("|").append(" --- |".repeat(width)).append('\n');
        for (List<String> row : rows.subList(1, rows.size())) {
            out.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return out.toString().stripTrailing();
    }

    // ── helpers ───────────────────────────────────────────────────

    /**
     * The page's own name for itself. {@code og:title} first — it is the one a
     * publisher curated for sharing, and it usually lacks the " | Site Name"
     * tail that {@code <title>} carries.
     */
    private static String title(org.jsoup.nodes.Document doc) {
        String og = doc.select("meta[property=og:title]").attr("content");
        if (!og.isBlank()) return collapse(og);
        String title = doc.title();
        if (!title.isBlank()) return collapse(title);
        Element h1 = doc.selectFirst("h1");
        return h1 == null ? "" : collapse(h1.text());
    }

    /** The language of a {@code <pre>}, from the usual class conventions. */
    private static String language(Element pre) {
        Element code = pre.selectFirst("code");
        String classes = (code == null ? pre : code).className();
        for (String token : classes.split("\\s+")) {
            if (token.startsWith("language-")) return token.substring("language-".length());
            if (token.startsWith("lang-")) return token.substring("lang-".length());
        }
        return "";
    }

    /**
     * An absolute URL for the attribute, falling back to the raw value.
     *
     * <p>jsoup's {@code absUrl} returns empty when there is no usable base —
     * which happens when the caller had no source URL. Keeping the relative
     * value then is better than dropping the link: it is still evidence of
     * where the page pointed.
     */
    private static String absolute(Element el, String attribute) {
        String abs = el.absUrl(attribute);
        return abs.isBlank() ? el.attr(attribute).trim() : abs;
    }

    /**
     * Escape what would otherwise become structure.
     *
     * <p>Not paranoid escaping: the goal is that a sentence from the page
     * cannot accidentally *become* a heading, a list or a link. Raw HTML is
     * neutered by escaping {@code <} — anything the page had inline stays
     * visible as text rather than being handed to a renderer.
     */
    static String escape(String text) {
        if (text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\', '`', '*', '_', '[', ']', '<' -> out.append('\\').append(c);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** One blank line between blocks; collapsed at the end by {@link #tidy}. */
    private static void block(StringBuilder out) {
        out.append("\n\n");
    }

    private static String prefixLines(String text, String prefix) {
        if (text.isEmpty()) return text;
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            out.append(prefix).append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Normalise the liberal block separators into readable Markdown.
     *
     * <p>Emitting {@code \n\n} around every block and cleaning up once is a
     * deliberate trade against tracking "did I already end a block" through the
     * recursion — that state is what makes hand-written serialisers produce
     * documents with four blank lines in one place and none in another.
     */
    private static String tidy(String markdown) {
        String out = markdown
                .replace("\r\n", "\n")
                .replaceAll("[ \t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
        if (out.length() > MAX_MARKDOWN_CHARS) {
            out = out.substring(0, MAX_MARKDOWN_CHARS).stripTrailing()
                    + "\n\n*(truncated)*";
        }
        return out;
    }
}
