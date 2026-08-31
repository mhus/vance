package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated {@code _index.md}. It exists so the collection is readable
 * outside the app — which means the text in it comes from foreign pages,
 * and foreign text in markdown is the interesting case.
 */
class LinksIndexTest {

    @Test
    void ungroupedEntriesLeadAndGroupsFollowInDeclaredOrder() {
        LinksConfig config = new LinksConfig(List.of("Second", "First"), List.of(
                entry("https://loose.example/", "Loose", null),
                entry("https://b.example/", "In first", "First"),
                entry("https://a.example/", "In second", "Second")),
                LinksConfig.DEFAULT_INDEX);

        String md = LinksApplication.renderIndex(config, "Reading");

        assertThat(md.indexOf("Loose")).isLessThan(md.indexOf("## Second"));
        assertThat(md.indexOf("## Second")).isLessThan(md.indexOf("## First"));
    }

    @Test
    void aTitleWithBracketsCannotBreakOutOfTheLinkLabel() {
        // The title is somebody else's og:title. An unescaped ] would end the
        // label early and leave the rest of it loose next to a broken URL.
        LinksConfig config = new LinksConfig(List.of(), List.of(
                entry("https://a.example/", "Read [this] now", null)),
                LinksConfig.DEFAULT_INDEX);

        String md = LinksApplication.renderIndex(config, "Reading");

        assertThat(md).contains("[Read \\[this\\] now](https://a.example/)");
    }

    @Test
    void aMultilineTeaserBecomesOneLineSoTheListStaysAList() {
        LinksConfig config = new LinksConfig(List.of(), List.of(
                new LinkEntry("https://a.example/", "T", "first\n\nsecond", null, null,
                        List.of(), null, null, null)),
                LinksConfig.DEFAULT_INDEX);

        String md = LinksApplication.renderIndex(config, "Reading");

        assertThat(md).contains("— first second");
    }

    @Test
    void theOwnNoteTravelsIntoTheIndex() {
        // The note is the half that cannot be recovered from the page, so the
        // one artefact that travels (chat, embed, export) must carry it.
        LinksConfig config = new LinksConfig(List.of(), List.of(
                new LinkEntry("https://a.example/", "T", null, null, null,
                        List.of(), "send to the team", null, null)),
                LinksConfig.DEFAULT_INDEX);

        assertThat(LinksApplication.renderIndex(config, "Reading"))
                .contains("— *send to the team*");
    }

    @Test
    void teaserAndNoteAppearInThatOrderAndStayDistinguishable() {
        LinksConfig config = new LinksConfig(List.of(), List.of(
                new LinkEntry("https://a.example/", "T", "what the page says", null, null,
                        List.of(), "why I kept it", null, null)),
                LinksConfig.DEFAULT_INDEX);

        assertThat(LinksApplication.renderIndex(config, "Reading"))
                .contains("— what the page says · *why I kept it*");
    }

    @Test
    void anAsteriskInBorrowedTextCannotItaliciseTheRestOfTheLine() {
        // The note is wrapped in *…*, so one unescaped asterisk inside would
        // close the emphasis and run it over everything after.
        LinksConfig config = new LinksConfig(List.of(), List.of(
                new LinkEntry("https://a.example/", "T", null, null, null,
                        List.of(), "read *this* first", null, null)),
                LinksConfig.DEFAULT_INDEX);

        assertThat(LinksApplication.renderIndex(config, "Reading"))
                .contains("*read \\*this\\* first*");
    }

    @Test
    void noTitleFallsBackToTheHost() {
        LinksConfig config = new LinksConfig(List.of(), List.of(
                entry("https://www.example.com/deep/page", null, null)),
                LinksConfig.DEFAULT_INDEX);

        assertThat(LinksApplication.renderIndex(config, "Reading"))
                .contains("[example.com](https://www.example.com/deep/page)");
    }

    @Test
    void anEmptyListSaysSoInsteadOfRenderingAnEmptyPage() {
        String md = LinksApplication.renderIndex(LinksConfig.empty(), "Reading");

        assertThat(md).contains("No links yet.");
    }

    @Test
    void theHeaderMarksThePageAsGenerated() {
        // Whoever finds this file has to know an edit here is lost.
        String md = LinksApplication.renderIndex(LinksConfig.empty(), "Reading");

        assertThat(md).startsWith("---\n$meta:\n  kind: workpage\n");
        assertThat(md).contains("Auto-generated");
    }

    @Test
    void aMultilineTitleBecomesOneLineSoTheMarkdownLinkSurvives() {
        // Escaping keeps a `]` from ending the label early, but a newline ends
        // the list item itself: the rest of the title lands as a loose
        // paragraph next to a bare URL in brackets. And the title is precisely
        // the field that comes off the foreign page.
        LinksConfig config = new LinksConfig(List.of(), List.of(
                entry("https://a.example/", "Async Rust\n\nrevisited", null)),
                LinksConfig.DEFAULT_INDEX);

        String md = LinksApplication.renderIndex(config, "Reading");

        assertThat(md).contains("- [Async Rust revisited](https://a.example/)");
    }

    @Test
    void aBackslashInTheManifestTitleStaysAValidYamlScalar() {
        // `title: "Docs\Links — Index"` is an invalid escape sequence, and a
        // generated page whose $meta does not parse is no longer a workpage.
        String md = LinksApplication.renderIndex(LinksConfig.empty(), "Docs\\Links");

        assertThat(md).contains("title: \"Docs\\\\Links — Index\"");
    }

    @Test
    void aMultilineManifestTitleDoesNotSplitTheHeader() {
        String md = LinksApplication.renderIndex(LinksConfig.empty(), "Reading\nlist");

        assertThat(md).contains("title: \"Reading list — Index\"");
        assertThat(md).contains("# Reading list\n");
    }

    private static LinkEntry entry(String url, String title, String group) {
        return new LinkEntry(url, title, null, null, group, List.of(), null, null, null);
    }
}
