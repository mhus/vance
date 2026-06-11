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
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class OpenAlexProtocolTest {

    private static final SearchScope SCOPE = SearchScope.of("acme", "alpha");
    private static final ProviderInstanceConfig CFG = new ProviderInstanceConfig(
            "openalex", "openalex",
            "https://api.openalex.org",
            "", Map.of());
    private static final ProviderInstanceConfig CFG_POLITE = new ProviderInstanceConfig(
            "openalex", "openalex",
            "https://api.openalex.org",
            "",
            Map.of("contactEmail", "me@x.de"));

    @Test
    void protocol_advertises_academic_modality() {
        OpenAlexProtocol p = new OpenAlexProtocol(new ObjectMapper(), mock(SimpleHttpClient.class));
        assertThat(p.modalitiesSupported()).containsExactly(SearchModality.ACADEMIC);
    }

    @Test
    void search_parses_results_with_inverted_abstract() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, """
                    {"results":[
                       {"id":"https://openalex.org/W12345",
                        "doi":"https://doi.org/10.1000/xyz",
                        "title":"On the elegance of small APIs",
                        "publication_year":2024,
                        "cited_by_count":42,
                        "type":"article",
                        "open_access":{"is_oa":true},
                        "authorships":[
                            {"author":{"display_name":"Ada Lovelace"}},
                            {"author":{"display_name":"Grace Hopper"}}
                        ],
                        "primary_location":{
                            "source":{"display_name":"Journal of Compilers"},
                            "landing_page_url":"https://example.org/paper"
                        },
                        "abstract_inverted_index":{
                            "Small":[0], "APIs":[1], "are":[2], "elegant":[3]
                        }}
                    ]}"""));
        OpenAlexProtocol p = new OpenAlexProtocol(new ObjectMapper(), http);
        SearchResult r = p.instantiate(CFG)
                .search(SearchRequest.normal("APIs", SearchModality.ACADEMIC, 5), SCOPE);

        assertThat(r.ok()).isTrue();
        assertThat(r.hits()).hasSize(1);
        SearchHit hit = r.hits().get(0);
        assertThat(hit.url()).isEqualTo("https://doi.org/10.1000/xyz");
        assertThat(hit.snippet()).contains("Ada Lovelace").contains("2024");
        assertThat(hit.extras())
                .containsEntry("publicationYear", 2024)
                .containsEntry("citedByCount", 42)
                .containsEntry("workType", "article")
                .containsEntry("openAccess", true)
                .containsEntry("venue", "Journal of Compilers");
        assertThat(hit.content()).isNotNull();
        assertThat(hit.content().inline()).isEqualTo(ContentInline.EMBED_TEXT);
        assertThat(hit.content().inlineText()).isEqualTo("Small APIs are elegant");
    }

    @Test
    void search_attaches_mailto_when_contact_email_present() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, "{\"results\":[]}"));
        OpenAlexProtocol p = new OpenAlexProtocol(new ObjectMapper(), http);
        p.instantiate(CFG_POLITE)
                .search(SearchRequest.normal("q", SearchModality.ACADEMIC, 1), SCOPE);

        ArgumentCaptor<URI> cap = ArgumentCaptor.forClass(URI.class);
        org.mockito.Mockito.verify(http).get(cap.capture(), any(), any());
        String url = cap.getValue().toString();
        assertThat(url).contains("mailto=me%40x.de");
    }

    @Test
    void search_omits_mailto_when_no_contact() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, "{\"results\":[]}"));
        OpenAlexProtocol p = new OpenAlexProtocol(new ObjectMapper(), http);
        p.instantiate(CFG)
                .search(SearchRequest.normal("q", SearchModality.ACADEMIC, 1), SCOPE);

        ArgumentCaptor<URI> cap = ArgumentCaptor.forClass(URI.class);
        org.mockito.Mockito.verify(http).get(cap.capture(), any(), any());
        assertThat(cap.getValue().toString()).doesNotContain("mailto=");
    }

    @Test
    void reconstructAbstract_assembles_sorted_terms() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String json = """
            {"the":[0,2],"quick":[1],"brown":[3],"fox":[4]}
            """;
        String rebuilt = OpenAlexProtocol.OpenAlexInstance.reconstructAbstract(
                om.readTree(json));
        assertThat(rebuilt).isEqualTo("the quick the brown fox");
    }
}
