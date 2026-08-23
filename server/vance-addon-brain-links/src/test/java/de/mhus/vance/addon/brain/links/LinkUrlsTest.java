package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/**
 * The URL is this app's primary key, so every test here is about the same
 * question: does the same page pasted twice end up as one entry, and does
 * something that is not a web page get in at all?
 */
class LinkUrlsTest {

    @Test
    void normalise_addsHttpsToABareHost() {
        // What a person actually pastes. Refusing it would make the common
        // case the error case.
        assertThat(LinkUrls.normalise("example.com/page"))
                .isEqualTo("https://example.com/page");
    }

    @Test
    void normalise_lowercasesTheHostButNotThePath() {
        // Hosts are case-insensitive, paths are not: /Guide and /guide can be
        // two different pages.
        assertThat(LinkUrls.normalise("https://EXAMPLE.com/Guide"))
                .isEqualTo("https://example.com/Guide");
    }

    @Test
    void normalise_dropsADefaultPortSoTheSamePageIsOneEntry() {
        assertThat(LinkUrls.normalise("https://example.com:443/a"))
                .isEqualTo("https://example.com/a");
        assertThat(LinkUrls.normalise("http://example.com:80/a"))
                .isEqualTo("http://example.com/a");
    }

    @Test
    void normalise_keepsANonDefaultPort() {
        assertThat(LinkUrls.normalise("http://example.com:8080/a"))
                .isEqualTo("http://example.com:8080/a");
    }

    @Test
    void normalise_givesAnEmptyPathASlash() {
        assertThat(LinkUrls.normalise("https://example.com"))
                .isEqualTo("https://example.com/");
    }

    @Test
    void normalise_keepsTheQuery() {
        // An id in the query is the address, not decoration.
        assertThat(LinkUrls.normalise("https://example.com/i?id=7&v=2"))
                .isEqualTo("https://example.com/i?id=7&v=2");
    }

    @Test
    void normalise_keepsTheFragmentSoASectionCanBeBookmarked() {
        assertThat(LinkUrls.normalise("https://example.com/guide#chapter-3"))
                .isEqualTo("https://example.com/guide#chapter-3");
    }

    @Test
    void identity_treatsTwoSectionsOfOnePageAsTwoLinks() {
        // The alternative — collapsing them — makes "add did nothing" the
        // outcome of bookmarking a second section, which is unexplainable.
        assertThat(LinkUrls.identity("https://example.com/g#a"))
                .isNotEqualTo(LinkUrls.identity("https://example.com/g#b"));
    }

    @Test
    void normalise_refusesJavascript() {
        // This value would end up as an href on a card the reader clicks.
        assertThatThrownBy(() -> LinkUrls.normalise("javascript:alert(1)"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("http(s)");
    }

    @Test
    void normalise_refusesOtherSchemes() {
        assertThatThrownBy(() -> LinkUrls.normalise("mailto:someone@example.com"))
                .isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> LinkUrls.normalise("data:text/html,<b>x"))
                .isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> LinkUrls.normalise("ftp://example.com/f"))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void normalise_refusesBlankAndHostless() {
        assertThatThrownBy(() -> LinkUrls.normalise("  "))
                .isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> LinkUrls.normalise("https:///nohost"))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void normalise_storesANonAsciiHostAsPunycode() {
        // URI.getHost() is null for a non-ASCII hostname, so asking it and
        // refusing the answer made a link straight out of the browser's address
        // bar unstorable — through no surface, and not by hand either, because
        // LinkEntry.fromMap normalises with the same function.
        assertThat(LinkUrls.normalise("https://münchen.de/veranstaltungen"))
                .isEqualTo("https://xn--mnchen-3ya.de/veranstaltungen");
    }

    @Test
    void identity_treatsBothSpellingsOfAnIdnHostAsOneLink() {
        assertThat(LinkUrls.identity("https://münchen.de/x"))
                .isEqualTo(LinkUrls.identity("https://xn--mnchen-3ya.de/x"));
    }

    @Test
    void normalise_acceptsAHostWithAnUnderscore() {
        // Also registry-based as far as URI is concerned, and also a real host
        // somebody pastes from an internal tool list.
        assertThat(LinkUrls.normalise("http://my_host.example.com/x"))
                .isEqualTo("http://my_host.example.com/x");
    }

    @Test
    void normalise_acceptsAPastedHostAndPortWithoutAScheme() {
        // The old rule was "contains a colon ⇒ it is a scheme", which read the
        // most likely paste in a list of internal tools as mailto:-shaped.
        assertThat(LinkUrls.normalise("192.168.1.5:8080/admin"))
                .isEqualTo("https://192.168.1.5:8080/admin");
        assertThat(LinkUrls.normalise("localhost:3000/x"))
                .isEqualTo("https://localhost:3000/x");
    }

    @Test
    void normalise_stillRefusesASchemePrefixThatIsNotAPort() {
        // The separation has to hold in both directions, or the port fix
        // reopens the javascript: hole.
        assertThatThrownBy(() -> LinkUrls.normalise("javascript:void(0)"))
                .isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> LinkUrls.normalise("data:text/html;base64,PHNjcmlwdD4="))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void normalise_acceptsASchemelessPasteWithANestedUrlInTheQuery() {
        // "contains ://" is not the same question as "starts with a scheme".
        assertThat(LinkUrls.normalise("example.com/out?u=https://target.example/x"))
                .isEqualTo("https://example.com/out?u=https://target.example/x");
    }

    @Test
    void normalise_refusesAPortThatIsNotAPort() {
        assertThatThrownBy(() -> LinkUrls.normalise("https://example.com:99999/a"))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void hostLabel_stripsWww() {
        assertThat(LinkUrls.hostLabel("https://www.example.com/a")).isEqualTo("example.com");
        assertThat(LinkUrls.hostLabel("https://blog.example.com/a"))
                .isEqualTo("blog.example.com");
    }

    @Test
    void hostLabel_showsAnIdnHostInTheSpellingAPersonRecognises() {
        // Stored as punycode so two spellings are one entry; xn--mnchen-3ya.de
        // on a card names no page anybody knows.
        assertThat(LinkUrls.hostLabel("https://xn--mnchen-3ya.de/x")).isEqualTo("münchen.de");
    }

    @Test
    void isHttp_isTheOneGuardForAStoredPicture() {
        assertThat(LinkUrls.isHttp("https://cdn.example/a.png")).isTrue();
        assertThat(LinkUrls.isHttp("javascript:alert(1)")).isFalse();
        assertThat(LinkUrls.isHttp("data:image/png;base64,AAA")).isFalse();
        assertThat(LinkUrls.isHttp(null)).isFalse();
    }
}
