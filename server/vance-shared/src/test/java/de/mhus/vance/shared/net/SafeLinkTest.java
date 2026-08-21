package de.mhus.vance.shared.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link SafeLink}. The allow-list is the point: the dangerous
 * set of schemes is not enumerable, so anything outside http/https/mailto is
 * refused whether or not anybody has heard of it.
 */
class SafeLinkTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/hit",
            "http://intranet.local/page",          // internal is fine — we do not fetch it
            "https://example.com:8443/a?b=c#d",
            "mailto:ford@example.com",
    })
    void safe_allowedSchemes_pass(String url) {
        assertThat(SafeLink.safe(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JavaScript:alert(1)",                 // scheme match is case-insensitive
            "data:text/html,<script>x</script>",   // the same problem in a different coat
            "vbscript:msgbox",
            "file:///etc/passwd",
            "/relative/path",                      // would resolve against our own origin
            "example.com/no-scheme",
            "   ",
    })
    void safe_everythingElse_isRefused(String url) {
        assertThat(SafeLink.safe(url)).isNull();
    }

    @Test
    void safe_nullAndBlank_areRefusedWithoutThrowing() {
        assertThat(SafeLink.safe(null)).isNull();
        assertThat(SafeLink.safe("")).isNull();
    }

    @Test
    void safe_trimsSurroundingWhitespace() {
        assertThat(SafeLink.safe("  https://example.com  ")).isEqualTo("https://example.com");
    }

    @Test
    void require_refusal_namesWhatIsAcceptable() {
        // The message is shown to whoever supplied the URL, so it has to say
        // what would work rather than only that this did not.
        assertThatThrownBy(() -> SafeLink.require("javascript:alert(1)"))
                .isInstanceOf(SafeLink.UnsafeLinkException.class)
                .hasMessageContaining("http")
                .hasMessageContaining("mailto");
    }

    @Test
    void hostOf_isLowercasedAndPortless() {
        assertThat(SafeLink.hostOf("https://Example.COM:8443/x")).isEqualTo("example.com");
    }

    @Test
    void hostOf_withoutAHost_isNull() {
        // A mailto: has no host, and an unsafe URL has no answer at all — the
        // audit entry simply carries no linkHost then.
        assertThat(SafeLink.hostOf("mailto:ford@example.com")).isNull();
        assertThat(SafeLink.hostOf("javascript:alert(1)")).isNull();
        assertThat(SafeLink.hostOf(null)).isNull();
    }
}
