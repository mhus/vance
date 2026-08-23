package de.mhus.vance.foot.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/**
 * {@link FileDiffRenderer#mergeForeground} — the overlay of a syntax token's
 * foreground colour onto a diff row style. The expected values are built with
 * JLine's own {@code foreground*} mutators, so the merge is checked against
 * "what JLine would have produced" rather than against hand-picked bits.
 */
class FileDiffRendererStyleTest {

    /** The default {@code ui.toolOutput.diffContext} style: {@code fg:bright-black}. */
    private static final AttributedStyle CONTEXT_ROW =
            AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);

    @Test
    void mergeForeground_tokenWithoutColour_keepsTheRowColour() {
        // Identifiers and punctuation are not tokenised — the highlighter
        // reports AttributedStyle.DEFAULT for them. Those characters must keep
        // the row colour instead of collapsing to colour index 0 (black).
        AttributedStyle merged =
                FileDiffRenderer.mergeForeground(CONTEXT_ROW, AttributedStyle.DEFAULT);

        assertThat(merged.getStyle()).isEqualTo(CONTEXT_ROW.getStyle());
        assertThat(merged.getMask()).isEqualTo(CONTEXT_ROW.getMask());
    }

    @Test
    void mergeForeground_indexedToken_replacesTheRowColour() {
        AttributedStyle token = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);

        AttributedStyle merged = FileDiffRenderer.mergeForeground(CONTEXT_ROW, token);

        AttributedStyle expected = CONTEXT_ROW.foreground(AttributedStyle.YELLOW);
        assertThat(merged.getStyle()).isEqualTo(expected.getStyle());
        assertThat(merged.getMask()).isEqualTo(expected.getMask());
    }

    @Test
    void mergeForeground_rgbToken_staysRgb() {
        // StyleParser maps fg:#rrggbb to foregroundRgb. Dropping the RGB flag
        // made the 24-bit value be read as a palette index.
        AttributedStyle token = AttributedStyle.DEFAULT.foregroundRgb(0xff8800);

        AttributedStyle merged = FileDiffRenderer.mergeForeground(CONTEXT_ROW, token);

        AttributedStyle expected = CONTEXT_ROW.foregroundRgb(0xff8800);
        assertThat(merged.getStyle()).isEqualTo(expected.getStyle());
        assertThat(merged.getMask()).isEqualTo(expected.getMask());
    }

    @Test
    void mergeForeground_keepsBackgroundAndAttributesOfTheRow() {
        AttributedStyle row = AttributedStyle.DEFAULT
                .background(AttributedStyle.GREEN)
                .bold()
                .foreground(AttributedStyle.WHITE);
        AttributedStyle token = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);

        AttributedStyle merged = FileDiffRenderer.mergeForeground(row, token);

        AttributedStyle expected = row.foreground(AttributedStyle.RED);
        assertThat(merged.getStyle()).isEqualTo(expected.getStyle());
        assertThat(merged.getMask()).isEqualTo(expected.getMask());
    }

    @Test
    void mergeForeground_rowWithoutColour_takesTheTokenColourWithoutInventingOne() {
        // e.g. diffAdd: "bold" — no fg: at all. An unstyled character must not
        // gain a foreground flag, a keyword must.
        AttributedStyle row = AttributedStyle.DEFAULT.bold();

        assertThat(FileDiffRenderer.mergeForeground(row, AttributedStyle.DEFAULT).getStyle())
                .isEqualTo(row.getStyle());

        AttributedStyle keyword = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
        assertThat(FileDiffRenderer.mergeForeground(row, keyword).getStyle())
                .isEqualTo(row.foreground(AttributedStyle.CYAN).getStyle());
    }
}
