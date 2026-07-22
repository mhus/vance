package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guards the terminal control-character sanitizer (code-review F3):
 * server-/LLM-supplied content must not carry ESC/OSC/C1 bytes to the
 * screen, while ordinary text (incl. newlines and tabs) is preserved.
 */
class TerminalSanitizerTest {

    private static final String ESC = "\u001B";
    private static final String CSI_C1 = "\u009B"; // single-byte CSI on some terminals
    private static final String DEL = "\u007F";
    private static final String BEL = "\u0007";

    @Test
    void sanitizeContent_stripsEscSequences() {
        // An OSC-52 clipboard write hidden behind ESC ] 52 ... BEL.
        String malicious = "hello" + ESC + "]52;c;ZXZpbA==" + BEL + " world";

        String cleaned = TerminalSanitizer.sanitizeContent(malicious);

        assertThat(cleaned).doesNotContain(ESC).doesNotContain(BEL);
        assertThat(cleaned).isEqualTo("hello]52;c;ZXZpbA== world");
    }

    @Test
    void sanitizeContent_keepsNewlineAndTab() {
        String text = "line1\n\tindented\nline3";

        assertThat(TerminalSanitizer.sanitizeContent(text)).isEqualTo(text);
    }

    @Test
    void sanitizeContent_stripsC1ControlRange() {
        String text = "a" + CSI_C1 + "31mred";

        assertThat(TerminalSanitizer.sanitizeContent(text)).isEqualTo("a31mred");
    }

    @Test
    void sanitizeContent_stripsDel() {
        assertThat(TerminalSanitizer.sanitizeContent("a" + DEL + "b")).isEqualTo("ab");
    }

    @Test
    void sanitizeContent_returnsSameInstanceWhenClean() {
        String clean = "just plain text 123";

        assertThat(TerminalSanitizer.sanitizeContent(clean)).isSameAs(clean);
    }

    @Test
    void sanitizeStrict_alsoStripsNewlineAndTab() {
        assertThat(TerminalSanitizer.sanitizeStrict("a\nb\tc")).isEqualTo("abc");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(TerminalSanitizer.sanitizeContent(null)).isEmpty();
        assertThat(TerminalSanitizer.sanitizeContent("")).isEmpty();
    }
}
