package de.mhus.vance.brain.zarniwoop.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.research.ContentInline;
import de.mhus.vance.toolpack.research.ContentReference;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The row is what the model actually reads, so these tests are about the two
 * things that can go wrong there: a body that was fetched and then silently
 * dropped, and untrusted text that arrives with structure intact.
 */
class SearchHitRowsTest {

    @Test
    void shape_carriesTitleUrlSnippetAndSource() {
        Map<String, Object> row = SearchHitRows.shape(hit("Paper", "https://a.test/1",
                "a teaser", "OpenAlex", null));

        assertThat(row).containsEntry("title", "Paper")
                .containsEntry("url", "https://a.test/1")
                .containsEntry("snippet", "a teaser")
                .containsEntry("source", "OpenAlex");
    }

    @Test
    void shape_omitsBlankSnippetAndSourceRatherThanSendingEmptyFields() {
        Map<String, Object> row = SearchHitRows.shape(hit("Paper", "https://a.test/1",
                "  ", null, null));

        assertThat(row).doesNotContainKey("snippet").doesNotContainKey("source");
    }

    @Test
    void shape_inlinesExtrasAsFirstClassFields() {
        // Not wrapped in a sub-map: the model should see doi/imageUrl/citedByCount
        // as fields of the hit, per modality.
        SearchHit hit = new SearchHit("Paper", "https://a.test/1", null, null,
                SearchModality.ACADEMIC, null, Map.of("doi", "10.1/x", "citedByCount", 42));

        Map<String, Object> row = SearchHitRows.shape(hit);

        assertThat(row).containsEntry("doi", "10.1/x").containsEntry("citedByCount", 42);
    }

    // ── the body ─────────────────────────────────────────────────────

    @Test
    void shape_surfacesTheInlineBodyTheSourceAlreadyShipped() {
        // The whole point of this change: abstracts and extracts were being
        // fetched and then dropped because nothing read hit.content().
        Map<String, Object> row = SearchHitRows.shape(hit("Paper", "https://a.test/1",
                null, null, embedded("We show that …")));

        assertThat(row).containsEntry("body", "We show that …");
    }

    @Test
    void shape_hasNoBodyWhenTheHitCarriesNone() {
        Map<String, Object> row = SearchHitRows.shape(hit("Web page", "https://a.test/1",
                "snippet only", null, null));

        assertThat(row).doesNotContainKey("body");
    }

    @Test
    void shape_omitsTheBodyOfAStashedReference() {
        // STASH_ON_DEMAND means the bytes need a loadContent call, and no caller
        // in the brain makes one yet. An empty field on every hit of such a
        // source would be worse than none.
        ContentReference stashed = new ContentReference(
                "c1", "application/pdf", 90_000, ContentInline.STASH_ON_DEMAND, null, null);

        Map<String, Object> row = SearchHitRows.shape(hit("Doc", "https://a.test/1",
                null, null, stashed));

        assertThat(row).doesNotContainKey("body");
    }

    @Test
    void shape_capsTheBodyAndMarksThatItWasCut() {
        String long1 = "word ".repeat(400);   // 2000 chars

        Map<String, Object> row = SearchHitRows.shape(hit("Paper", "https://a.test/1",
                null, null, embedded(long1)));

        String body = (String) row.get("body");
        assertThat(body).hasSizeLessThanOrEqualTo(SearchHitRows.MAX_BODY_CHARS + 1)
                // The model must be able to tell a cut-off sentence from a
                // complete one, or it quotes half of one as the whole thing.
                .endsWith(SearchHitRows.ELLIPSIS);
    }

    @Test
    void shape_leavesABodyUnderTheCapUntouched() {
        String short1 = "A short abstract.";

        Map<String, Object> row = SearchHitRows.shape(hit("Paper", "https://a.test/1",
                null, null, embedded(short1)));

        assertThat(row).containsEntry("body", short1);
        assertThat((String) row.get("body")).doesNotEndWith(SearchHitRows.ELLIPSIS);
    }

    @Test
    void shape_cutsTheBodyAtAWordBoundaryWhenOneIsNear() {
        // A cut mid-word reads like a broken value rather than an abbreviated one.
        String text = "x".repeat(SearchHitRows.MAX_BODY_CHARS - 10) + " thisWordIsCutAway";

        Map<String, Object> row = SearchHitRows.shape(hit("Paper", "https://a.test/1",
                null, null, embedded(text)));

        assertThat((String) row.get("body"))
                .isEqualTo("x".repeat(SearchHitRows.MAX_BODY_CHARS - 10)
                        + SearchHitRows.ELLIPSIS);
    }

    @Test
    void shape_bodyWinsOverAnExtrasKeyOfTheSameName() {
        // The content channel is the authoritative body; a provider that puts a
        // `body` key in extras must not shadow it.
        SearchHit hit = new SearchHit("Paper", "https://a.test/1", null, null,
                SearchModality.ACADEMIC, embedded("the real abstract"),
                Map.of("body", "something else"));

        assertThat(SearchHitRows.shape(hit)).containsEntry("body", "the real abstract");
    }

    // ── untrusted content ────────────────────────────────────────────

    @Test
    void shape_collapsesWhitespaceInEveryUntrustedField() {
        // Newlines in a hit could inject structure once an engine renders the row
        // into a templated prompt. This used to hold for research_search only —
        // research_rich and research_search_expert built the row themselves.
        SearchHit hit = new SearchHit(
                "Title\nwith break", "https://a.test/1",
                "snippet\nwith break", "src\nbreak",
                SearchModality.WEB, embedded("body\nwith break"), Map.of());

        Map<String, Object> row = SearchHitRows.shape(hit);

        assertThat((String) row.get("title")).doesNotContain("\n");
        assertThat((String) row.get("snippet")).doesNotContain("\n");
        assertThat((String) row.get("source")).doesNotContain("\n");
        assertThat((String) row.get("body")).doesNotContain("\n");
    }

    @Test
    void shape_capsTheSnippetSoItCannotBeUsedInsteadOfTheBody() {
        // Half a cost control is none: with only the body capped, a source that
        // wants to fill the context window sends the text as a snippet.
        String huge = "s".repeat(50_000);

        Map<String, Object> row = SearchHitRows.shape(
                hit("T", "https://a.test/1", huge, huge, null));

        assertThat((String) row.get("snippet"))
                .hasSize(SearchHitRows.MAX_FIELD_CHARS + 1)
                .endsWith(SearchHitRows.ELLIPSIS);
        assertThat((String) row.get("source"))
                .hasSize(SearchHitRows.MAX_FIELD_CHARS + 1);
    }

    @Test
    void shape_capsATextualExtraToo() {
        // Extras are as foreign as the fields beside them, and an uncapped one
        // is the same hole in the same wall.
        SearchHit hit = new SearchHit("T", "https://a.test/1", null, null,
                SearchModality.WEB, null, Map.of("abstract", "x".repeat(50_000)));

        Map<String, Object> row = SearchHitRows.shape(hit);

        assertThat((String) row.get("abstract"))
                .hasSize(SearchHitRows.MAX_FIELD_CHARS + 1);
    }

    @Test
    void shape_leavesTheUrlAlone() {
        // A URL is not free text; collapsing it would corrupt an escaped path.
        String url = "https://a.test/path%20with%20escape?q=a+b";

        assertThat(SearchHitRows.shape(hit("T", url, null, null, null)))
                .containsEntry("url", url);
    }

    @Test
    void shape_keepsInsertionOrderSoTheBodyComesLast() {
        // The body is by far the longest field; reading a row is easier when the
        // identifying fields come first.
        Map<String, Object> row = SearchHitRows.shape(hit("T", "https://a.test/1",
                "s", "src", embedded("b")));

        assertThat(new LinkedHashMap<>(row).keySet()).containsExactly(
                "title", "url", "snippet", "source", "body");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static SearchHit hit(String title, String url, @Nullable String snippet,
                                 @Nullable String source, @Nullable ContentReference content) {
        return new SearchHit(title, url, snippet, source,
                SearchModality.WEB, content, Map.of());
    }

    private static ContentReference embedded(String text) {
        return new ContentReference("c1", "text/plain", text.length(),
                ContentInline.EMBED_TEXT, text, null);
    }
}
