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
    void hostLabel_stripsWww() {
        assertThat(LinkUrls.hostLabel("https://www.example.com/a")).isEqualTo("example.com");
        assertThat(LinkUrls.hostLabel("https://blog.example.com/a"))
                .isEqualTo("blog.example.com");
    }
}
