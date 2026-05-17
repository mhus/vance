package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure helpers on {@link WebFetchTool} — flag parsing and the
 * HTML→text transform. The HTTP path requires the real network and
 * is exercised by integration tests, not here.
 */
class WebFetchToolTest {

    // ─── parseFlags ─────────────────────────────────────────────────────

    @Test
    void parseFlags_nullAndBlank_returnEmpty() {
        assertThat(WebFetchTool.parseFlags(null)).isEmpty();
        assertThat(WebFetchTool.parseFlags("")).isEmpty();
        assertThat(WebFetchTool.parseFlags("   ")).isEmpty();
    }

    @Test
    void parseFlags_acceptsCommaSeparated() {
        assertThat(WebFetchTool.parseFlags("no-llms,text"))
                .containsExactly(WebFetchTool.FLAG_NO_LLMS, WebFetchTool.FLAG_TEXT);
    }

    @Test
    void parseFlags_acceptsSpaceSeparated() {
        assertThat(WebFetchTool.parseFlags("no-llms text"))
                .containsExactly(WebFetchTool.FLAG_NO_LLMS, WebFetchTool.FLAG_TEXT);
    }

    @Test
    void parseFlags_lowercases() {
        assertThat(WebFetchTool.parseFlags("NO-LLMS, Text"))
                .containsExactly(WebFetchTool.FLAG_NO_LLMS, WebFetchTool.FLAG_TEXT);
    }

    @Test
    void parseFlags_dropsUnknownTokens() {
        assertThat(WebFetchTool.parseFlags("text bogus,no-llms,xyz"))
                .containsExactlyInAnyOrder(WebFetchTool.FLAG_TEXT, WebFetchTool.FLAG_NO_LLMS);
    }

    @Test
    void parseFlags_isIdempotentOnDuplicates() {
        assertThat(WebFetchTool.parseFlags("text,text, text"))
                .containsExactly(WebFetchTool.FLAG_TEXT);
    }

    @Test
    void parseFlags_recognises_raw_as_opt_out_for_html_extraction() {
        // Sister flag of 'text' but inverse semantics — confirms the
        // post-2026-05-17 default flip (HTML→text on by default,
        // 'raw' opts out). Without this entry in KNOWN_FLAGS the
        // token would be silently dropped and the LLM's "give me the
        // markup" intent ignored.
        assertThat(WebFetchTool.parseFlags("raw"))
                .containsExactly(WebFetchTool.FLAG_RAW);
        assertThat(WebFetchTool.parseFlags("no-llms, raw"))
                .containsExactlyInAnyOrder(
                        WebFetchTool.FLAG_NO_LLMS, WebFetchTool.FLAG_RAW);
    }

    @Test
    void parseFlags_keeps_text_token_as_no_op_for_backwards_compat() {
        // 'text' used to flip text-extraction on; now text is the
        // default and the flag is a documented no-op. Old callers
        // (saved prompts, kit-shipped recipe YAMLs) must keep
        // working — keeping the token in KNOWN_FLAGS means it is
        // still parsed (and silently ignored at invoke-time).
        assertThat(WebFetchTool.parseFlags("text"))
                .containsExactly(WebFetchTool.FLAG_TEXT);
    }

    // ─── htmlToText ─────────────────────────────────────────────────────

    @Test
    void htmlToText_extractsVisibleProseAndDecodesEntities() {
        String html = "<html><body><p>Hello &amp; goodbye</p></body></html>";

        String text = WebFetchTool.htmlToText(html);

        assertThat(text).isEqualTo("Hello & goodbye");
    }

    @Test
    void htmlToText_dropsScriptAndStyleContent() {
        String html = "<html><head><style>.x{color:red}</style></head>"
                + "<body>visible<script>alert('xss')</script></body></html>";

        String text = WebFetchTool.htmlToText(html);

        assertThat(text).doesNotContain("color")
                .doesNotContain("alert")
                .contains("visible");
    }

    @Test
    void htmlToText_preservesParagraphBoundariesAsNewlines() {
        String html = "<p>first paragraph</p><p>second paragraph</p>";

        String text = WebFetchTool.htmlToText(html);

        assertThat(text).contains("first paragraph")
                .contains("second paragraph")
                .contains("\n");
    }

    @Test
    void htmlToText_handlesPlainTextInputGracefully() {
        // Jsoup wraps non-HTML in a synthetic body; the visible text
        // round-trips unchanged.
        String text = WebFetchTool.htmlToText("just plain text");

        assertThat(text).isEqualTo("just plain text");
    }

    @Test
    void htmlToText_emptyAndNullReturnEmpty() {
        assertThat(WebFetchTool.htmlToText(null)).isEmpty();
        assertThat(WebFetchTool.htmlToText("")).isEmpty();
    }

    @Test
    void htmlToText_brBecomesNewline() {
        String html = "line1<br>line2<br/>line3";

        String text = WebFetchTool.htmlToText(html);

        assertThat(text)
                .contains("line1")
                .contains("line2")
                .contains("line3")
                .contains("\n");
    }
}
