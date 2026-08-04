package de.mhus.vance.foot.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StyleParser}.
 *
 * <p>Verifies parsing of named colours, bright variants, hex RGB,
 * modifiers, and edge cases (blank, null, unknown tokens).
 */
class StyleParserTest {

    // ── Null / blank ──────────────────────────────────────────────

    @Test
    void parse_null_returnsNull() {
        assertNull(StyleParser.parse(null));
    }

    @Test
    void parse_blank_returnsNull() {
        assertNull(StyleParser.parse(""));
        assertNull(StyleParser.parse("   "));
        assertNull(StyleParser.parse("\t\n"));
    }

    @Test
    void parse_emptyTokens_returnsNull() {
        assertNull(StyleParser.parse(",,,"));
    }

    @Test
    void parse_unknownTokensOnly_returnsNull() {
        assertNull(StyleParser.parse("flurgle,gnarf"));
    }

    // ── Named foreground colours ──────────────────────────────────

    @Test
    void parse_fgRed() {
        AttributedStyle s = StyleParser.parse("fg:red");
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED), s);
    }

    @Test
    void parse_fgGreen() {
        AttributedStyle s = StyleParser.parse("fg:green");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN), s);
    }

    @Test
    void parse_fgBlue() {
        AttributedStyle s = StyleParser.parse("fg:blue");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE), s);
    }

    @Test
    void parse_fgYellow() {
        AttributedStyle s = StyleParser.parse("fg:yellow");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW), s);
    }

    @Test
    void parse_fgCyan() {
        AttributedStyle s = StyleParser.parse("fg:cyan");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN), s);
    }

    @Test
    void parse_fgMagenta() {
        AttributedStyle s = StyleParser.parse("fg:magenta");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA), s);
    }

    @Test
    void parse_fgBlack() {
        AttributedStyle s = StyleParser.parse("fg:black");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK), s);
    }

    @Test
    void parse_fgWhite() {
        AttributedStyle s = StyleParser.parse("fg:white");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE), s);
    }

    // ── Bright variants ───────────────────────────────────────────

    @Test
    void parse_fgBrightBlack() {
        AttributedStyle s = StyleParser.parse("fg:bright-black");
        assertEquals(AttributedStyle.DEFAULT.foreground(
                AttributedStyle.BRIGHT + AttributedStyle.BLACK), s);
    }

    @Test
    void parse_fgBrightRed() {
        AttributedStyle s = StyleParser.parse("fg:bright-red");
        assertEquals(AttributedStyle.DEFAULT.foreground(
                AttributedStyle.BRIGHT + AttributedStyle.RED), s);
    }

    // ── Gray / grey alias ─────────────────────────────────────────

    @Test
    void parse_fgGray_mapsToBrightBlack() {
        AttributedStyle s = StyleParser.parse("fg:gray");
        assertEquals(AttributedStyle.DEFAULT.foreground(
                AttributedStyle.BRIGHT + AttributedStyle.BLACK), s);
    }

    @Test
    void parse_fgGrey_mapsToBrightBlack() {
        AttributedStyle s = StyleParser.parse("fg:grey");
        assertEquals(AttributedStyle.DEFAULT.foreground(
                AttributedStyle.BRIGHT + AttributedStyle.BLACK), s);
    }

    // ── Default ───────────────────────────────────────────────────

    @Test
    void parse_fgDefault() {
        AttributedStyle s = StyleParser.parse("fg:default");
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foregroundDefault(), s);
    }

    // ── Background ────────────────────────────────────────────────

    @Test
    void parse_bgRed() {
        AttributedStyle s = StyleParser.parse("bg:red");
        assertEquals(AttributedStyle.DEFAULT.background(AttributedStyle.RED), s);
    }

    @Test
    void parse_bgBrightBlack() {
        AttributedStyle s = StyleParser.parse("bg:bright-black");
        assertEquals(AttributedStyle.DEFAULT.background(
                AttributedStyle.BRIGHT + AttributedStyle.BLACK), s);
    }

    // ── Hex RGB ───────────────────────────────────────────────────

    @Test
    void parse_hex6() {
        AttributedStyle s = StyleParser.parse("fg:#ff0000");
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foregroundRgb(0xff0000), s);
    }

    @Test
    void parse_hex3() {
        AttributedStyle s = StyleParser.parse("fg:#f00");
        // #f00 expands to #ff0000
        assertEquals(AttributedStyle.DEFAULT.foregroundRgb(0xff0000), s);
    }

    @Test
    void parse_hex6_bg() {
        AttributedStyle s = StyleParser.parse("bg:#00ff00");
        assertEquals(AttributedStyle.DEFAULT.backgroundRgb(0x00ff00), s);
    }

    // ── Modifiers ─────────────────────────────────────────────────

    @Test
    void parse_bold() {
        AttributedStyle s = StyleParser.parse("bold");
        assertEquals(AttributedStyle.DEFAULT.bold(), s);
    }

    @Test
    void parse_faint() {
        AttributedStyle s = StyleParser.parse("faint");
        assertEquals(AttributedStyle.DEFAULT.faint(), s);
    }

    @Test
    void parse_italic() {
        AttributedStyle s = StyleParser.parse("italic");
        assertEquals(AttributedStyle.DEFAULT.italic(), s);
    }

    @Test
    void parse_underline() {
        AttributedStyle s = StyleParser.parse("underline");
        assertEquals(AttributedStyle.DEFAULT.underline(), s);
    }

    @Test
    void parse_blink() {
        AttributedStyle s = StyleParser.parse("blink");
        assertEquals(AttributedStyle.DEFAULT.blink(), s);
    }

    @Test
    void parse_inverse() {
        AttributedStyle s = StyleParser.parse("inverse");
        assertEquals(AttributedStyle.DEFAULT.inverse(), s);
    }

    @Test
    void parse_conceal() {
        AttributedStyle s = StyleParser.parse("conceal");
        assertEquals(AttributedStyle.DEFAULT.conceal(), s);
    }

    @Test
    void parse_crossedOut() {
        AttributedStyle s = StyleParser.parse("crossed-out");
        assertEquals(AttributedStyle.DEFAULT.crossedOut(), s);
    }

    @Test
    void parse_strikethrough_alias() {
        AttributedStyle s = StyleParser.parse("strikethrough");
        assertEquals(AttributedStyle.DEFAULT.crossedOut(), s);
    }

    // ── Combined tokens ───────────────────────────────────────────

    @Test
    void parse_fgRedBold() {
        AttributedStyle s = StyleParser.parse("fg:red,bold");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold(), s);
    }

    @Test
    void parse_multipleModifiers() {
        AttributedStyle s = StyleParser.parse("fg:cyan,bold,italic");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold().italic(), s);
    }

    @Test
    void parse_fgAndBg() {
        AttributedStyle s = StyleParser.parse("fg:white,bg:red,bold");
        assertEquals(AttributedStyle.DEFAULT
                .foreground(AttributedStyle.WHITE)
                .background(AttributedStyle.RED)
                .bold(), s);
    }

    // ── Whitespace tolerance ───────────────────────────────────────

    @Test
    void parse_whitespaceTolerated() {
        AttributedStyle s = StyleParser.parse("  fg:red ,  bold  ");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold(), s);
    }

    @Test
    void parse_caseInsensitive() {
        AttributedStyle s = StyleParser.parse("FG:RED,BOLD");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold(), s);
    }

    // ── Unknown tokens ignored ────────────────────────────────────

    @Test
    void parse_unknownTokenIgnored_validTokenApplied() {
        AttributedStyle s = StyleParser.parse("fg:red,unknown,bold");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold(), s);
    }

    @Test
    void parse_invalidHex_fallsBackToNull() {
        // #xyz is not valid hex and not a named colour → token ignored
        AttributedStyle s = StyleParser.parse("fg:#xyz");
        assertNull(s);
    }

    // ── toAnsi output verification ────────────────────────────────

    @Test
    void parsedStyle_toAnsi_producesCorrectSGR() {
        AttributedStyle s = StyleParser.parse("fg:red");
        assertNotNull(s);
        String ansi = s.toAnsi();
        // JLine produces SGR sequences; fg:red should contain "31"
        assertTrue(ansi.contains("31"), "Expected red (31) in: " + ansi);
    }

    @Test
    void parsedStyle_toAnsi_boldContains1() {
        AttributedStyle s = StyleParser.parse("bold");
        assertNotNull(s);
        assertTrue(s.toAnsi().contains("1"), "Expected bold (1) in: " + s.toAnsi());
    }
}
