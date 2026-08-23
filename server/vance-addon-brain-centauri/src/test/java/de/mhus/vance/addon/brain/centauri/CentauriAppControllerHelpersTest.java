package de.mhus.vance.addon.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.feed.FeedDirection;
import org.junit.jupiter.api.Test;

/**
 * The two deterministic functions the rest of the controller trusts: the one
 * that keeps a foreign headline inside its YAML scalar, and the one that
 * decides which path gets authorised. The second exists because of a real
 * bypass, which is reason enough for it to have a test.
 */
class CentauriAppControllerHelpersTest {

    // ── frontmatter quoting ──────────────────────────────────────────

    @Test
    void yaml_escapesTheQuoteThatWouldEndTheScalar() {
        assertThat(CentauriAppController.yaml("He said \"no\""))
                .isEqualTo("\"He said \\\"no\\\"\"");
    }

    @Test
    void yaml_escapesTheBackslashBeforeTheQuote() {
        // Order matters: quoting first and escaping backslashes afterwards
        // would double the backslash this method just introduced.
        assertThat(CentauriAppController.yaml("C:\\path")).isEqualTo("\"C:\\\\path\"");
    }

    @Test
    void yaml_keepsALineBreakInsideTheScalar() {
        // A raw newline would end the scalar and leave the rest of the headline
        // standing where a key belongs — the whole frontmatter stops parsing.
        assertThat(CentauriAppController.yaml("Berlin\nupdated"))
                .isEqualTo("\"Berlin\\nupdated\"");
    }

    @Test
    void yaml_escapesControlCharactersNumerically() {
        assertThat(CentauriAppController.yaml("a\u0001b")).isEqualTo("\"a\\x01b\"");
        assertThat(CentauriAppController.yaml("a\u007fb")).isEqualTo("\"a\\x7fb\"");
    }

    @Test
    void yaml_leavesAColonAlone_becauseTheQuotesCarryIt() {
        assertThat(CentauriAppController.yaml("Berlin: a history"))
                .isEqualTo("\"Berlin: a history\"");
    }

    // ── the path that gets authorised ────────────────────────────────

    @Test
    void normalisePath_refusesTraversalRatherThanCollapsingIt() {
        // Collapsing would put a second normalisation step between the check
        // and the write, which is the shape of the bug this guards against.
        assertThatThrownBy(() -> CentauriAppController.normalisePath("clips/../../_vance/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
    }

    @Test
    void normalisePath_stripsLeadingSlashesSoTheCheckSeesWhatIsWritten() {
        // "/_vance/manuals/x.md" passed the reserved-prefix rule as an ordinary
        // document and landed as "_vance/manuals/x.md".
        assertThat(CentauriAppController.normalisePath("/_vance/manuals/x.md"))
                .isEqualTo("_vance/manuals/x.md");
    }

    @Test
    void normalisePath_refusesAnEmptySegment() {
        assertThatThrownBy(() -> CentauriAppController.normalisePath("clips//x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalisePath_refusesAnEmptyPath() {
        assertThatThrownBy(() -> CentauriAppController.normalisePath("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalisePath_appendsTheMarkdownExtension() {
        assertThat(CentauriAppController.normalisePath("clips/berlin"))
                .isEqualTo("clips/berlin.md");
        assertThat(CentauriAppController.normalisePath("clips/berlin.md"))
                .isEqualTo("clips/berlin.md");
    }

    // ── direction ────────────────────────────────────────────────────

    @Test
    void direction_defaultsToOlderForAnythingItDoesNotKnow() {
        assertThat(CentauriAppController.direction(null)).isEqualTo(FeedDirection.OLDER);
        assertThat(CentauriAppController.direction("  ")).isEqualTo(FeedDirection.OLDER);
        assertThat(CentauriAppController.direction("sideways")).isEqualTo(FeedDirection.OLDER);
        assertThat(CentauriAppController.direction(" NEWER ")).isEqualTo(FeedDirection.NEWER);
    }
}
