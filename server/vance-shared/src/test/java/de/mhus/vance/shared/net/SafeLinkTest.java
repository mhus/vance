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

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/search?q=a b",     // space
            "https://example.com/100%discount",     // '%' without a hex pair
            "https://example.com/a|b",
            "https://example.com/a[1].pdf",
            "https://example.com/x#frag ment",
            "https://exa_mple.com/x",               // underscore host
            "https://例え.jp/",                      // IDN, not punycode
    })
    void safe_acceptsWhatTheBrowserAccepts(String url) {
        // The twin safeUrl.ts uses the WHATWG parser and passes every one of
        // these; java.net.URI (RFC 2396) rejects all of them. Parsing the whole
        // URL here made the two disagree for reasons that have nothing to do with
        // safety — and told the user "needs an absolute http, https or mailto
        // URL" about a link that is exactly that.
        assertThat(SafeLink.safe(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "//example.com/protocol-relative",      // no scheme = relative
            "java\tscript:alert(1)",                // browsers strip the tab
            "java\nscript:alert(1)",
            "\u00A0javascript:alert(1)",       // NBSP survives trim()
            "https://exa\nmple.com/x",              // displays as two lines, resolves as one
            ":no-scheme-name",
    })
    void safe_refusesEverythingThatWouldReadAsOneThingAndResolveAsAnother(String url) {
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

    @Test
    void hostOf_answersForAuthoritiesJavaUriCallsHostless() {
        // java.net.URI#getHost() returns null for every authority that is not
        // server-based, so the audit line lost exactly the field it exists for —
        // and lost it indistinguishably, since null is also the honest answer for
        // mailto:. Both of these are ordinary links to show a colleague.
        assertThat(SafeLink.hostOf("https://exa_mple.com/x")).isEqualTo("exa_mple.com");
        assertThat(SafeLink.hostOf("https://例え.jp/")).isEqualTo("例え.jp");
    }

    @Test
    void hostOf_dropsUserinfoAndStopsAtTheAuthorityBoundary() {
        // Userinfo must not reach the audit trail, and the split is on the *last*
        // '@' the way a browser does it — otherwise https://a@evil.com/ would be
        // recorded as host "a".
        assertThat(SafeLink.hostOf("https://user:pw@example.com/x")).isEqualTo("example.com");
        assertThat(SafeLink.hostOf("https://a@b@example.com/x")).isEqualTo("example.com");
        // A backslash ends the authority for a browser too, so recording
        // "good.com" is what actually gets visited.
        assertThat(SafeLink.hostOf("https://good.com\\@evil.com/")).isEqualTo("good.com");
        assertThat(SafeLink.hostOf("https://example.com")).isEqualTo("example.com");
        assertThat(SafeLink.hostOf("https://example.com?q=1")).isEqualTo("example.com");
    }

    @Test
    void hostOf_keepsABracketedIpv6LiteralIntact() {
        assertThat(SafeLink.hostOf("https://[2001:db8::1]:8443/x")).isEqualTo("[2001:db8::1]");
    }
}
