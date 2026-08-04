package de.mhus.vance.foot.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.config.FootConfig;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ColorResolver}.
 *
 * <p>Verifies that config values are correctly resolved into
 * {@link AttributedStyle} instances, and that the {@link #toAnsi} /
 * {@link #wrap} helpers produce correct ANSI SGR sequences.
 */
class ColorResolverTest {

    private FootConfig mockConfig() {
        FootConfig config = mock(FootConfig.class);
        FootConfig.Ui ui = mock(FootConfig.Ui.class);
        FootConfig.Colors colors = new FootConfig.Colors();
        when(config.getUi()).thenReturn(ui);
        when(ui.getColors()).thenReturn(colors);
        return config;
    }

    // ── Config → AttributedStyle resolution ───────────────────────

    @Test
    void defaultConfig_workerIsGreen() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.worker();
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN), s);
    }

    @Test
    void defaultConfig_chatIsNull() {
        ColorResolver cr = new ColorResolver(mockConfig());
        assertNull(cr.chat());
    }

    @Test
    void defaultConfig_infoIsBrightBlack() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.info();
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foreground(
                AttributedStyle.BRIGHT + AttributedStyle.BLACK), s);
    }

    @Test
    void defaultConfig_warnIsYellow() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.warn();
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW), s);
    }

    @Test
    void defaultConfig_errorIsRed() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.error();
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED), s);
    }

    @Test
    void defaultConfig_notifyInfoIsCyanBold() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.notifyInfo();
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold(), s);
    }

    @Test
    void defaultConfig_userEchoIsInverse() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.userEcho();
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.inverse(), s);
    }

    @Test
    void defaultConfig_completionIsBlue() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.completion();
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE), s);
    }

    @Test
    void defaultConfig_ghostTextIsFaint() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.ghostText();
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foreground(
                AttributedStyle.BRIGHT + AttributedStyle.BLACK).faint(), s);
    }

    @Test
    void defaultConfig_statusBusyIsYellow() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.statusBusy();
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW), s);
    }

    @Test
    void defaultConfig_statusDimIsFaint() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.statusDim();
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foreground(
                AttributedStyle.BRIGHT + AttributedStyle.BLACK).faint(), s);
    }

    @Test
    void defaultConfig_statusContextIsCyan() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.statusContext();
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN), s);
    }

    @Test
    void defaultConfig_systemMessageIsFaint() {
        ColorResolver cr = new ColorResolver(mockConfig());
        AttributedStyle s = cr.systemMessage();
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foreground(
                AttributedStyle.BRIGHT + AttributedStyle.BLACK).faint(), s);
    }

    // ── Custom config ─────────────────────────────────────────────

    @Test
    void customConfig_overridesWorker() {
        FootConfig config = mockConfig();
        config.getUi().getColors().setWorker("fg:blue,bold");
        ColorResolver cr = new ColorResolver(config);
        AttributedStyle s = cr.worker();
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE).bold(), s);
    }

    @Test
    void customConfig_emptyString_resolvesToNull() {
        FootConfig config = mockConfig();
        config.getUi().getColors().setWorker("");
        ColorResolver cr = new ColorResolver(config);
        assertNull(cr.worker());
    }

    @Test
    void customConfig_hexColour() {
        FootConfig config = mockConfig();
        config.getUi().getColors().setChat("fg:#ff8800");
        ColorResolver cr = new ColorResolver(config);
        AttributedStyle s = cr.chat();
        assertNotNull(s);
        assertEquals(AttributedStyle.DEFAULT.foregroundRgb(0xff8800), s);
    }

    // ── toAnsi() ──────────────────────────────────────────────────

    @Test
    void toAnsi_nullStyle_returnsEmptyString() {
        assertEquals("", ColorResolver.toAnsi(null));
    }

    @Test
    void toAnsi_fgRed_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
        String ansi = ColorResolver.toAnsi(s);
        // Must be a usable CSI escape sequence, not just the bare SGR
        // parameter body that AttributedStyle.toAnsi() returns.
        assertEquals("\u001b[31m", ansi, "Expected full CSI red sequence, got: " + ansi);
    }

    @Test
    void toAnsi_fgGreen_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
        assertTrue(ColorResolver.toAnsi(s).contains("32"));
    }

    @Test
    void toAnsi_fgBlue_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE);
        assertTrue(ColorResolver.toAnsi(s).contains("34"));
    }

    @Test
    void toAnsi_fgBrightBlack_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.foreground(
                AttributedStyle.BRIGHT + AttributedStyle.BLACK);
        assertTrue(ColorResolver.toAnsi(s).contains("90"));
    }

    @Test
    void toAnsi_fgYellow_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
        assertTrue(ColorResolver.toAnsi(s).contains("33"));
    }

    @Test
    void toAnsi_fgCyan_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
        assertTrue(ColorResolver.toAnsi(s).contains("36"));
    }

    @Test
    void toAnsi_bold_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.bold();
        assertTrue(ColorResolver.toAnsi(s).contains("1"));
    }

    @Test
    void toAnsi_faint_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.faint();
        assertTrue(ColorResolver.toAnsi(s).contains("2"));
    }

    @Test
    void toAnsi_italic_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.italic();
        assertTrue(ColorResolver.toAnsi(s).contains("3"));
    }

    @Test
    void toAnsi_inverse_producesCorrectSGR() {
        AttributedStyle s = AttributedStyle.DEFAULT.inverse();
        assertTrue(ColorResolver.toAnsi(s).contains("7"));
    }

    @Test
    void toAnsi_multipleModifiers_combined() {
        AttributedStyle s = AttributedStyle.DEFAULT
                .foreground(AttributedStyle.RED)
                .bold()
                .italic();
        String ansi = ColorResolver.toAnsi(s);
        // JLine's toAnsi() returns the SGR parameter string (e.g. "3;31;1")
        assertTrue(ansi.contains("31"), "Expected red (31) in: " + ansi);
        assertTrue(ansi.contains("1"), "Expected bold (1) in: " + ansi);
        assertTrue(ansi.contains("3"), "Expected italic (3) in: " + ansi);
    }

    @Test
    void toAnsi_fgAndBg_combined() {
        AttributedStyle s = AttributedStyle.DEFAULT
                .foreground(AttributedStyle.WHITE)
                .background(AttributedStyle.RED);
        String ansi = ColorResolver.toAnsi(s);
        assertTrue(ansi.contains("37"), "Expected white fg (37) in: " + ansi);
        assertTrue(ansi.contains("41"), "Expected red bg (41) in: " + ansi);
    }

    @Test
    void toAnsi_defaultStyle_returnsEmpty() {
        // AttributedStyle.DEFAULT.toAnsi() may return a space or empty —
        // either way, no SGR escape sequence should be present.
        String result = ColorResolver.toAnsi(AttributedStyle.DEFAULT);
        assertTrue(result.trim().isEmpty(), "Expected no SGR codes, got: " + result);
    }

    // ── wrap() ───────────────────────────────────────────────────

    @Test
    void wrap_nullStyle_returnsTextUnchanged() {
        assertEquals("hello", ColorResolver.wrap(null, "hello"));
    }

    @Test
    void wrap_fgRed_wrapsText() {
        AttributedStyle s = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
        String result = ColorResolver.wrap(s, "hello");
        assertTrue(result.contains("31"), "Expected red SGR in: " + result);
        assertTrue(result.endsWith("\u001b[0m"), "Should end with reset");
        assertTrue(result.contains("hello"), "Should contain the text");
    }

    @Test
    void wrap_inverse_wrapsText() {
        AttributedStyle s = AttributedStyle.DEFAULT.inverse();
        String result = ColorResolver.wrap(s, "test");
        assertTrue(result.contains("7"), "Expected inverse (7) in: " + result);
        assertTrue(result.endsWith("\u001b[0m"));
        assertTrue(result.contains("test"));
    }

    // ── ANSI_RESET constant ──────────────────────────────────────

    @Test
    void ansiReset_isEsc0m() {
        assertEquals("\u001b[0m", ColorResolver.ANSI_RESET);
    }
}
