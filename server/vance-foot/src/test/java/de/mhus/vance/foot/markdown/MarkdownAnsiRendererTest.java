package de.mhus.vance.foot.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.config.FootConfig;
import java.util.List;
import java.util.stream.Collectors;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarkdownAnsiRendererTest {

    private MarkdownAnsiRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new MarkdownAnsiRenderer(new FootConfig());
    }

    /** Convenience — render and return the plain (style-stripped) lines. */
    private List<String> plainLines(String md) {
        return renderer.render(md).stream()
                .map(AttributedString::toString)
                .collect(Collectors.toList());
    }

    @Test
    void heading_pads_blank_line_above_and_below() {
        List<String> out = plainLines("Intro line\n## Section\nbody");
        assertThat(out).containsExactly("Intro line", "", "## Section", "", "body");
    }

    @Test
    void heading_at_start_does_not_emit_leading_blank() {
        List<String> out = plainLines("# Title\nbody");
        assertThat(out).containsExactly("# Title", "", "body");
    }

    @Test
    void heading_followed_by_blank_does_not_double_blank() {
        List<String> out = plainLines("## Section\n\nbody");
        assertThat(out).containsExactly("## Section", "", "body");
    }

    @Test
    void bold_markers_are_stripped_in_plain_view() {
        List<String> out = plainLines("hello **world**");
        assertThat(out).containsExactly("hello world");
    }

    @Test
    void italic_markers_are_stripped_in_plain_view() {
        List<String> out = plainLines("a *b* c _d_ e");
        assertThat(out).containsExactly("a b c d e");
    }

    @Test
    void inline_code_markers_are_stripped_in_plain_view() {
        List<String> out = plainLines("call `methodName()` here");
        assertThat(out).containsExactly("call methodName() here");
    }

    @Test
    void bold_renders_with_ansi_bold_escape() {
        String ansi = renderer.render("hello **world**").get(0).toAnsi();
        assertThat(ansi).contains("[1m").contains("world");
    }

    @Test
    void italic_renders_with_ansi_italic_escape() {
        String ansi = renderer.render("plain *emph* tail").get(0).toAnsi();
        assertThat(ansi).contains("[3m").contains("emph");
    }

    @Test
    void fenced_code_block_is_emitted_verbatim() {
        String md = "intro\n```java\nint x = 1;\n```\ntail";
        List<String> out = plainLines(md);
        assertThat(out).containsExactly("intro", "```java", "int x = 1;", "```", "tail");
    }

    @Test
    void inline_markers_inside_code_fence_stay_raw() {
        String md = "```\n**not bold**\n```";
        List<String> out = plainLines(md);
        assertThat(out).containsExactly("```", "**not bold**", "```");
    }

    @Test
    void table_renders_as_aligned_box() {
        String md = "| A | Beta |\n|---|---|\n| 1 | longer |\n| 22 | x |";
        List<String> out = plainLines(md);
        // Header row + body rows; we don't assert exact border chars but
        // do require the count and that the longer column content is padded.
        assertThat(out).hasSize(6); // top border + header + sep + 2 body + bottom border
        assertThat(out.get(1)).contains("A").contains("Beta");
        assertThat(out.get(3)).contains("1").contains("longer");
        assertThat(out.get(4)).contains("22").contains("x");
    }

    @Test
    void table_columns_align_with_inline_marker_widths() {
        String md = "| Punkt | Lösung |\n|---|---|\n| **Workspace-Pod** | runs locally |";
        List<String> out = plainLines(md);
        // Body row's first column should NOT contain the raw `**` markers
        // (they get translated to bold and don't take visual space).
        assertThat(out.get(3)).contains("Workspace-Pod").doesNotContain("**Workspace-Pod**");
    }

    @Test
    void blockquote_gets_box_prefix_and_strips_marker() {
        List<String> out = plainLines("> quoted advice");
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).startsWith("│").contains("quoted advice");
    }

    @Test
    void empty_input_produces_empty_list() {
        assertThat(renderer.render("")).isEmpty();
    }

    @Test
    void plain_text_passes_through() {
        assertThat(plainLines("Just one line.\nAnd another."))
                .containsExactly("Just one line.", "And another.");
    }

    @Test
    void prose_wraps_at_configured_width() {
        FootConfig config = new FootConfig();
        config.getUi().getMarkdown().setWrapWidth(40);
        MarkdownAnsiRenderer narrow = new MarkdownAnsiRenderer(config);
        // 120 chars, no markdown — should split at word boundaries to fit 40.
        String md = "The find function works again now and I am verifying the v2 spec against the actual implementation in code.";
        List<String> out = narrow.render(md).stream()
                .map(AttributedString::toString)
                .collect(Collectors.toList());
        assertThat(out.size()).isGreaterThan(1);
        for (String line : out) {
            assertThat(line.length()).isLessThanOrEqualTo(40);
        }
        // Reassembled text must equal the original (after re-joining with one space).
        assertThat(String.join(" ", out)).isEqualTo(md);
    }

    @Test
    void wrap_disabled_keeps_long_line_intact() {
        FootConfig config = new FootConfig();
        config.getUi().getMarkdown().setWrapWidth(0);
        MarkdownAnsiRenderer off = new MarkdownAnsiRenderer(config);
        String md = "x".repeat(200);
        assertThat(off.render(md)).hasSize(1);
        assertThat(off.render(md).get(0).toString()).hasSize(200);
    }

    @Test
    void table_rows_are_not_wrapped() {
        FootConfig config = new FootConfig();
        config.getUi().getMarkdown().setWrapWidth(20);
        MarkdownAnsiRenderer narrow = new MarkdownAnsiRenderer(config);
        String md = "| col1 | col2 |\n|---|---|\n| this cell content is well past twenty cols | second |";
        List<String> out = narrow.render(md).stream()
                .map(AttributedString::toString)
                .collect(Collectors.toList());
        // The body data row keeps its full cell content despite the
        // narrow wrap width — tables manage their own layout.
        assertThat(out).anySatisfy(line ->
                assertThat(line).contains("this cell content is well past twenty cols"));
    }

    @Test
    void code_fence_body_is_not_wrapped() {
        FootConfig config = new FootConfig();
        config.getUi().getMarkdown().setWrapWidth(20);
        MarkdownAnsiRenderer narrow = new MarkdownAnsiRenderer(config);
        String longCode = "very-long-identifier-that-would-otherwise-wrap-into-pieces();";
        String md = "```\n" + longCode + "\n```";
        List<String> out = narrow.render(md).stream()
                .map(AttributedString::toString)
                .collect(Collectors.toList());
        assertThat(out).contains(longCode);
    }

    @Test
    void blockquote_continuation_lines_keep_box_prefix() {
        FootConfig config = new FootConfig();
        config.getUi().getMarkdown().setWrapWidth(30);
        MarkdownAnsiRenderer narrow = new MarkdownAnsiRenderer(config);
        String md = "> the quote is long enough that it must wrap across multiple lines";
        List<String> out = narrow.render(md).stream()
                .map(AttributedString::toString)
                .collect(Collectors.toList());
        assertThat(out.size()).isGreaterThan(1);
        for (String line : out) {
            assertThat(line).startsWith("│ ");
        }
    }

    @Test
    void overlong_unbroken_token_is_kept_intact() {
        FootConfig config = new FootConfig();
        config.getUi().getMarkdown().setWrapWidth(10);
        MarkdownAnsiRenderer narrow = new MarkdownAnsiRenderer(config);
        // URL-like token longer than the budget — we'd rather emit it
        // as one over-long line than mid-cut it.
        String url = "https://example.com/this/is/a/very/long/path";
        List<String> out = narrow.render(url).stream()
                .map(AttributedString::toString)
                .collect(Collectors.toList());
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isEqualTo(url);
    }
}
