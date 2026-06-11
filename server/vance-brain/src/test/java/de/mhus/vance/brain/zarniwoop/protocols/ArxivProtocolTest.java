package de.mhus.vance.brain.zarniwoop.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.zarniwoop.protocols.SimpleHttpClient.Response;
import de.mhus.vance.toolpack.research.ContentInline;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArxivProtocolTest {

    private static final SearchScope SCOPE = SearchScope.of("acme", "alpha");
    private static final ProviderInstanceConfig CFG = new ProviderInstanceConfig(
            "arxiv", "arxiv",
            "https://export.arxiv.org/api",
            "", Map.of());

    private static final String ATOM_SAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:arxiv="http://arxiv.org/schemas/atom">
              <entry>
                <id>http://arxiv.org/abs/2401.00001v1</id>
                <updated>2024-01-01T00:00:00Z</updated>
                <published>2024-01-01T00:00:00Z</published>
                <title>Attention Is All You Need (Revisited)</title>
                <summary>We revisit the attention mechanism and show that
                small refinements yield meaningful improvements.</summary>
                <author><name>A. Researcher</name></author>
                <author><name>B. Collaborator</name></author>
                <link href="http://arxiv.org/abs/2401.00001v1" rel="alternate" type="text/html"/>
                <link href="http://arxiv.org/pdf/2401.00001v1" rel="related" type="application/pdf"/>
                <arxiv:primary_category xmlns:arxiv="http://arxiv.org/schemas/atom" term="cs.LG"/>
              </entry>
            </feed>
            """;

    @Test
    void protocol_advertises_academic_modality() {
        ArxivProtocol p = new ArxivProtocol(mock(SimpleHttpClient.class));
        assertThat(p.modalitiesSupported()).containsExactly(SearchModality.ACADEMIC);
    }

    @Test
    void search_parses_atom_entries() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, ATOM_SAMPLE));
        ArxivProtocol p = new ArxivProtocol(http);

        SearchResult r = p.instantiate(CFG)
                .search(SearchRequest.normal("attention", SearchModality.ACADEMIC, 5), SCOPE);

        assertThat(r.ok()).isTrue();
        assertThat(r.hits()).hasSize(1);
        SearchHit hit = r.hits().get(0);
        assertThat(hit.title()).isEqualTo("Attention Is All You Need (Revisited)");
        assertThat(hit.url()).isEqualTo("http://arxiv.org/abs/2401.00001v1");
        assertThat(hit.snippet()).contains("A. Researcher").contains("2024").contains("cs.LG");
        assertThat(hit.extras())
                .containsEntry("publicationYear", 2024)
                .containsEntry("primaryCategory", "cs.LG")
                .containsEntry("pdfUrl", "http://arxiv.org/pdf/2401.00001v1");
        assertThat(hit.content()).isNotNull();
        assertThat(hit.content().inline()).isEqualTo(ContentInline.EMBED_TEXT);
        assertThat(hit.content().inlineText())
                .startsWith("We revisit the attention mechanism");
    }

    @Test
    void search_rejects_wrong_modality() {
        ArxivProtocol p = new ArxivProtocol(mock(SimpleHttpClient.class));
        SearchResult r = p.instantiate(CFG)
                .search(SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE);
        assertThat(r.ok()).isFalse();
        assertThat(r.errorMessage()).contains("not supported");
    }

    @Test
    void parser_handles_empty_feed() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200,
                        "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>"));
        ArxivProtocol p = new ArxivProtocol(http);
        SearchResult r = p.instantiate(CFG)
                .search(SearchRequest.normal("q", SearchModality.ACADEMIC, 5), SCOPE);
        assertThat(r.ok()).isTrue();
        assertThat(r.hits()).isEmpty();
    }
}
