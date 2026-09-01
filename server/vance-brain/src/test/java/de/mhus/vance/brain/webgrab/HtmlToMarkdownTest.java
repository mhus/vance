package de.mhus.vance.brain.webgrab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What a grabbed page has to survive with: structure, working links, and no
 * accidental Markdown.
 */
class HtmlToMarkdownTest {

    private static String md(String html) {
        return HtmlToMarkdown.convert(html, "https://example.com/blog/post").markdown();
    }

    // ── structure ─────────────────────────────────────────────────

    @Test
    void keepsHeadingsAsHeadings() {
        assertThat(md("<h1>Title</h1><h3>Sub</h3>"))
                .contains("# Title")
                .contains("### Sub");
    }

    @Test
    void keepsParagraphsSeparated() {
        assertThat(md("<p>One</p><p>Two</p>")).isEqualTo("One\n\nTwo");
    }

    @Test
    void keepsUnorderedAndOrderedLists() {
        assertThat(md("<ul><li>a</li><li>b</li></ul>")).isEqualTo("- a\n- b");
        assertThat(md("<ol><li>a</li><li>b</li></ol>")).isEqualTo("1. a\n2. b");
    }

    @Test
    void indentsNestedLists() {
        assertThat(md("<ul><li>a<ul><li>a1</li></ul></li><li>b</li></ul>"))
                .isEqualTo("- a\n  - a1\n- b");
    }

    @Test
    void keepsCodeBlocksFencedAndUnescaped() {
        String out = md("<pre><code class=\"language-java\">int a = b[0] * 2;</code></pre>");

        assertThat(out).startsWith("```java\n");
        // The whole point of a fence: what is inside is not Markdown.
        assertThat(out).contains("int a = b[0] * 2;");
    }

    @Test
    void keepsBlockquotes() {
        assertThat(md("<blockquote><p>said</p></blockquote>")).isEqualTo("> said");
    }

    @Test
    void keepsInlineEmphasisAndCode() {
        assertThat(md("<p>a <strong>b</strong> <em>c</em> <code>d</code></p>"))
                .isEqualTo("a **b** *c* `d`");
    }

    // ── links and images ──────────────────────────────────────────

    /** A saved page whose links all point at "/about" is one you cannot follow. */
    @Test
    void makesLinksAbsoluteAgainstTheSourceUrl() {
        assertThat(md("<p><a href=\"/about\">About</a></p>"))
                .isEqualTo("[About](https://example.com/about)");
    }

    @Test
    void makesImagesAbsolute() {
        assertThat(md("<p><img src=\"pic.png\" alt=\"Pic\"></p>"))
                .isEqualTo("![Pic](https://example.com/blog/pic.png)");
    }

    /** An anchor with nowhere to go is text, not a link the reader can click. */
    @Test
    void rendersUnfollowableAnchorsAsPlainText() {
        assertThat(md("<p><a href=\"javascript:x()\">Go</a></p>")).isEqualTo("Go");
        assertThat(md("<p><a>Bare</a></p>")).isEqualTo("Bare");
    }

    /**
     * A fragment is followable once there is a source URL to resolve it
     * against — in a saved copy, a footnote link that reaches the original
     * page's footnote is the useful behaviour. Without a base it resolves to
     * nothing, and then it is text.
     */
    @Test
    void resolvesFragmentsAgainstTheSourceButNotWithoutOne() {
        assertThat(md("<p><a href=\"#fn1\">1</a></p>"))
                .isEqualTo("[1](https://example.com/blog/post#fn1)");
        assertThat(HtmlToMarkdown.convert("<p><a href=\"#fn1\">1</a></p>", "").markdown())
                .isEqualTo("1");
    }

    // ── escaping ──────────────────────────────────────────────────

    /**
     * Page text must not become document structure. This is the failure that
     * shows up as a heading in the middle of a sentence.
     */
    @Test
    void escapesTextThatWouldOtherwiseBecomeMarkdown() {
        assertThat(md("<p>a * b _ c [d] `e`</p>"))
                .isEqualTo("a \\* b \\_ c \\[d\\] \\`e\\`");
    }

    @Test
    void neutersInlineHtmlLeftInTheText() {
        assertThat(md("<p>use &lt;script&gt; carefully</p>"))
                .isEqualTo("use \\<script> carefully");
    }

    // ── the content heuristic ─────────────────────────────────────

    @Test
    void dropsChrome() {
        String out = md("<body><nav>Home Menu</nav><p>Body</p>"
                + "<footer>Imprint</footer><aside>Ads</aside>"
                + "<script>evil()</script></body>");

        assertThat(out).isEqualTo("Body");
    }

    @Test
    void prefersTheArticleOverThePage() {
        String out = md("<body><div>Site chrome everywhere</div>"
                + "<article><p>The actual article body</p></article></body>");

        assertThat(out).isEqualTo("The actual article body");
    }

    /** A listing page is a stack of teasers; the first is not the content. */
    @Test
    void picksTheLargestArticleAmongSeveral() {
        String out = md("<body><article><p>Teaser</p></article>"
                + "<article><p>" + "The real one. ".repeat(40) + "</p></article></body>");

        assertThat(out).startsWith("The real one.");
        assertThat(out).doesNotContain("Teaser");
    }

    /**
     * A landmark holding almost nothing is a landmark on a page whose content
     * sits elsewhere — trusting it would produce an empty document from a page
     * that plainly has text.
     */
    @Test
    void ignoresAnEmptyLandmarkWhenTheBodyHasTheText() {
        String out = md("<body><main>Skip</main><div><p>"
                + "Everything worth keeping. ".repeat(30) + "</p></div></body>");

        assertThat(out).contains("Everything worth keeping.");
    }

    /**
     * Inside an article a {@code <header>} is the title block, not chrome —
     * dropping it would take the headline with it.
     */
    @Test
    void keepsTheHeaderInsideAnArticleButNotThePageHeader() {
        assertThat(md("<body><article><header><h1>Headline</h1></header>"
                + "<p>Body</p></article></body>")).isEqualTo("# Headline\n\nBody");
        assertThat(md("<body><header><h1>Site</h1></header><p>Body</p></body>"))
                .isEqualTo("Body");
    }

    // ── tables ────────────────────────────────────────────────────

    @Test
    void rendersARectangularTableAsGfm() {
        String out = md("<table><tr><th>A</th><th>B</th></tr>"
                + "<tr><td>1</td><td>2</td></tr></table>");

        assertThat(out).isEqualTo("| A | B |\n| --- | --- |\n| 1 | 2 |");
    }

    /**
     * A layout table rendered as a grid is worse than its cells as text — the
     * reader gets a one-column table where the page had a sidebar.
     */
    @Test
    void fallsBackToTextForANonRectangularTable() {
        String out = md("<table><tr><td>only</td></tr>"
                + "<tr><td>a</td><td>b</td></tr></table>");

        assertThat(out).doesNotContain("| --- |").contains("only");
    }

    @Test
    void escapesPipesInsideCells() {
        String out = md("<table><tr><th>A</th><th>B</th></tr>"
                + "<tr><td>a|b</td><td>c</td></tr></table>");

        assertThat(out).contains("| a\\|b | c |");
    }

    // ── title ─────────────────────────────────────────────────────

    @Test
    void prefersTheOpenGraphTitle() {
        var result = HtmlToMarkdown.convert(
                "<html><head><title>Post | Site</title>"
                + "<meta property=\"og:title\" content=\"Post\"></head>"
                + "<body><p>x</p></body></html>", "https://example.com/");

        assertThat(result.title()).isEqualTo("Post");
    }

    @Test
    void fallsBackFromTitleToTheFirstHeading() {
        assertThat(HtmlToMarkdown.convert("<body><h1>Only heading</h1></body>", "")
                .title()).isEqualTo("Only heading");
        assertThat(HtmlToMarkdown.convert("<body><p>no title anywhere</p></body>", "")
                .title()).isEmpty();
    }

    // ── robustness ────────────────────────────────────────────────

    @Test
    void survivesEmptyAndBrokenInput() {
        assertThat(md("")).isEmpty();
        assertThat(md("<p>unclosed <b>bold")).isEqualTo("unclosed **bold**");
    }

    @Test
    void collapsesRunsOfBlankLines() {
        assertThat(md("<div><div><div><p>a</p></div></div></div><p>b</p>"))
                .isEqualTo("a\n\nb");
    }

    @Test
    void truncatesRatherThanReturningAnUnboundedDocument() {
        String huge = "<p>" + "x".repeat(HtmlToMarkdown.MAX_MARKDOWN_CHARS + 5000) + "</p>";

        String out = md(huge);

        assertThat(out).endsWith("*(truncated)*");
        assertThat(out.length()).isLessThan(HtmlToMarkdown.MAX_MARKDOWN_CHARS + 100);
    }
}
