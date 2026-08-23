package de.mhus.vance.addon.brain.mastodon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The HTML shapes Mastodon actually emits, taken from the measured page. */
class StatusHtmlTest {

    @Test
    void toText_keepsHashtagsWhole() {
        // The real shape: the tag name sits in a nested inline <span>. A
        // stripper that spaces every tag boundary yields "# WindfallTax".
        String html = "<p>calls for a <a href=\"https://mastodon.scot/tags/WindfallTax\" "
                + "class=\"mention hashtag\" rel=\"nofollow noopener\" target=\"_blank\">"
                + "#<span>WindfallTax</span></a> on <a href=\"https://mastodon.scot/tags/oil\" "
                + "class=\"mention hashtag\">#<span>oil</span></a> companies</p>";

        assertThat(StatusHtml.toText(html))
                .isEqualTo("calls for a #WindfallTax on #oil companies");
    }

    @Test
    void toText_separatesParagraphsWithABlankLine() {
        String html = "<p><strong>Attacco con spada alla scuola</strong></p>"
                + "<p>La vittima è una ragazza di 17 anni</p>";

        assertThat(StatusHtml.toText(html)).isEqualTo(
                "Attacco con spada alla scuola\n\nLa vittima è una ragazza di 17 anni");
    }

    @Test
    void toText_brBecomesASingleNewline() {
        String html = "<p>The Insignia 32-inch Fire TV falls to $69.99<br>"
                + "<a href=\"https://www.androidauthority.com/x?a=1&amp;b=2\">androidauthority.com</a></p>";

        assertThat(StatusHtml.toText(html)).isEqualTo(
                "The Insignia 32-inch Fire TV falls to $69.99\nandroidauthority.com");
    }

    @Test
    void toText_decodesEntities() {
        // Every linked URL in a status carries &amp; in its query string.
        assertThat(StatusHtml.toText("<p>a &amp; b &lt;c&gt; &quot;d&quot; &#8230;</p>"))
                .isEqualTo("a & b <c> \"d\" …");
    }

    @Test
    void toText_keepsTheHiddenPartsOfAShortenedUrl() {
        // Mastodon hides the scheme and the tail of a long URL for display;
        // honouring that here would hand out a truncated link.
        String html = "<p><a href=\"https://example.org/very/long/path\">"
                + "<span class=\"invisible\">https://</span>"
                + "<span class=\"ellipsis\">example.org/very</span>"
                + "<span class=\"invisible\">/long/path</span></a></p>";

        assertThat(StatusHtml.toText(html)).isEqualTo("https://example.org/very/long/path");
    }

    @Test
    void toText_emptyContentStaysEmpty() {
        // Measured: 1 entry in 40 has content "" — an image with no words.
        assertThat(StatusHtml.toText("")).isEmpty();
        assertThat(StatusHtml.toText(null)).isEmpty();
        assertThat(StatusHtml.toText("   ")).isEmpty();
    }

    @Test
    void collapse_doesNotReparsePlainText() {
        // The reason collapse() exists next to toLine(): running already-plain
        // text through the parser again would read "< b" as a tag and eat the
        // rest of the line.
        assertThat(StatusHtml.collapse("a < b, and\n\nc  >  d"))
                .isEqualTo("a < b, and c > d");
    }

    @Test
    void collapse_squeezesEveryWhitespaceRun() {
        assertThat(StatusHtml.collapse("  two \t\n words  ")).isEqualTo("two words");
        assertThat(StatusHtml.collapse(null)).isEmpty();
    }
}
