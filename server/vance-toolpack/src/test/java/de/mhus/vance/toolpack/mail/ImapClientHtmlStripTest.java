package de.mhus.vance.toolpack.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-private HTML stripping + truncation
 * helpers in {@link ImapClient}. The full {@code previewMessage}
 * pipeline is covered by IMAP integration tests; these guard the
 * pure-string transforms.
 */
class ImapClientHtmlStripTest {

    @Test
    void htmlToText_strips_tags_and_keeps_text() {
        String html = "<p>Hello <b>World</b>!</p>";
        assertThat(ImapClient.htmlToText(html)).isEqualTo("Hello World!");
    }

    @Test
    void htmlToText_drops_style_and_script_blocks() {
        String html = "<html><head><style>.x{color:red}</style></head>"
                + "<body><script>alert(1)</script><p>Body</p></body></html>";
        String text = ImapClient.htmlToText(html);
        assertThat(text).isEqualTo("Body");
        assertThat(text).doesNotContain("color:red", "alert");
    }

    @Test
    void htmlToText_inserts_breaks_between_blocks() {
        String html = "<div>Line 1</div><div>Line 2</div>";
        String text = ImapClient.htmlToText(html);
        assertThat(text).contains("Line 1").contains("Line 2");
        assertThat(text.indexOf("\n")).isGreaterThan(0);
    }

    @Test
    void htmlToText_drops_inline_image_cid_references() {
        String html = "<p>Look at this:</p><img src=\"cid:logo.png\" alt=\"logo\"/>"
                + "<p>End.</p>";
        String text = ImapClient.htmlToText(html);
        assertThat(text).doesNotContain("cid:", "logo.png");
        assertThat(text).contains("Look at this").contains("End.");
    }

    @Test
    void htmlToText_handles_empty_input() {
        assertThat(ImapClient.htmlToText("")).isEqualTo("");
        assertThat(ImapClient.htmlToText(null)).isEqualTo("");
    }

    @Test
    void truncate_passes_through_when_under_limit() {
        assertThat(ImapClient.truncate("hello", 100)).isEqualTo("hello");
    }

    @Test
    void truncate_adds_marker_when_over_limit() {
        String long_ = "a".repeat(200);
        String result = ImapClient.truncate(long_, 50);
        assertThat(result).startsWith("a".repeat(50));
        assertThat(result).contains("[...truncated 150 chars]");
    }

    @Test
    void truncate_zero_means_unlimited() {
        String s = "a".repeat(200);
        assertThat(ImapClient.truncate(s, 0)).isEqualTo(s);
    }
}
