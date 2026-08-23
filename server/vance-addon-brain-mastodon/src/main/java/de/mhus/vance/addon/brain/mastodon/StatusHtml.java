package de.mhus.vance.addon.brain.mastodon;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.jspecify.annotations.Nullable;

/**
 * A status body is HTML; a {@code FeedItem} is text.
 *
 * <p>Parsed with jsoup rather than stripped with a regex, and that is a
 * decision with a reason. The tree already carries three hand-rolled strippers
 * ({@code WebFetchTool.htmlToText}, {@code HackerNewsProtocol.stripHtml},
 * {@code ImapClient}) and jsoup is already a brain dependency — a fourth regex
 * would be the cheapest thing to write and the most likely to be wrong on the
 * shape Mastodon actually emits:
 *
 * <pre>{@code
 * <a href="…/tags/WindfallTax" class="mention hashtag">#<span>WindfallTax</span></a>
 * }</pre>
 *
 * <p>The tag name sits inside a nested inline {@code <span>}. A stripper that
 * puts a space at every tag boundary turns that into {@code # WindfallTax}; one
 * that puts none turns {@code <p>a</p><p>b</p>} into {@code ab}. Both are
 * wrong, and the difference is the inline/block distinction — which is what an
 * HTML parser knows and a regex does not.
 *
 * <p>Entity decoding comes free with the parse, which matters: statuses carry
 * {@code &amp;} in every query string of every linked URL.
 */
final class StatusHtml {

    private StatusHtml() {
        /* helpers only */
    }

    /**
     * Readable text, paragraphs kept as blank lines.
     *
     * <p>{@code <span class="invisible">} is kept rather than dropped: Mastodon
     * uses it to shorten a displayed URL ({@code https://} + host +
     * {@code …/rest}), so honouring the class would hand out a truncated link.
     * Here the whole URL is wanted.
     */
    static String toText(@Nullable String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(html.length());
        NodeTraversor.traverse(new NodeVisitor() {

            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(text.getWholeText());
                } else if (node instanceof Element element && "br".equals(element.tagName())) {
                    out.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element && isBlock(element)) {
                    out.append("\n\n");
                }
            }
        }, Jsoup.parseBodyFragment(html).body());

        return collapseBlankLines(out.toString()).trim();
    }

    /**
     * One line, for a title or a summary — every run of whitespace becomes a
     * single space.
     */
    static String toLine(@Nullable String html) {
        return collapse(toText(html));
    }

    /**
     * Whitespace collapsed on text that is <b>already plain</b>.
     *
     * <p>Separate from {@link #toLine} on purpose: {@code spoiler_text} and the
     * output of {@link #toText} are plain text, and running them through the
     * parser again would treat {@code a < b} as the start of a tag and eat the
     * rest of the line.
     */
    static String collapse(@Nullable String plain) {
        return plain == null ? "" : collapseWhitespace(plain);
    }

    /** True when this element ends a paragraph of text. */
    private static boolean isBlock(Element element) {
        return switch (element.tagName()) {
            case "p", "div", "blockquote", "li", "h1", "h2", "h3", "h4", "h5", "h6",
                 "pre", "ul", "ol", "table", "tr" -> true;
            default -> false;
        };
    }

    private static String collapseBlankLines(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int newlines = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                newlines++;
                if (newlines <= 2) {
                    out.append(c);
                }
            } else {
                newlines = 0;
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String collapseWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = !out.isEmpty();
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(c);
            }
        }
        return out.toString();
    }
}
