package de.mhus.vance.brain.zarniwoop.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.zarniwoop.protocols.SimpleHttpClient.Response;
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

class PubMedProtocolTest {

    private static final SearchScope SCOPE = SearchScope.of("acme", "alpha");
    private static final ProviderInstanceConfig CFG = new ProviderInstanceConfig(
            "pubmed", "pubmed",
            "https://eutils.ncbi.nlm.nih.gov/entrez/eutils",
            "", Map.of());
    private static final ProviderInstanceConfig CFG_WITH_KEY = new ProviderInstanceConfig(
            "pubmed", "pubmed",
            "https://eutils.ncbi.nlm.nih.gov/entrez/eutils",
            "",
            Map.of("contactEmail", "me@x.de", "apiKey", "abc123"));

    private static final String ESEARCH_JSON = """
            {"header":{"type":"esearch","version":"0.3"},
             "esearchresult":{
                "count":"42","retmax":"2","retstart":"0",
                "idlist":["42359401","42358844"]}}
            """;

    private static final String ESUMMARY_JSON = """
            {"header":{"type":"esummary","version":"0.3"},
             "result":{
                "uids":["42359401","42358844"],
                "42359401":{
                   "uid":"42359401",
                   "pubdate":"2026 Jun 10",
                   "source":"Front Plant Sci",
                   "authors":[
                       {"name":"Gain H","authtype":"Author"},
                       {"name":"Banerjee J","authtype":"Author"}],
                   "title":"CRISPR-based crop improvement.",
                   "volume":"17",
                   "pubtype":["Journal Article","Review"],
                   "articleids":[
                       {"idtype":"pubmed","value":"42359401"},
                       {"idtype":"pmc","value":"PMC13290587"},
                       {"idtype":"doi","value":"10.3389/fpls.2026.1864496"}]},
                "42358844":{
                   "uid":"42358844",
                   "pubdate":"2025",
                   "source":"Nat Methods",
                   "authors":[
                       {"name":"Doe A"},
                       {"name":"Roe B"},
                       {"name":"Foo C"},
                       {"name":"Bar D"}],
                   "title":"Method paper.",
                   "articleids":[
                       {"idtype":"pubmed","value":"42358844"}]}}}
            """;

    @Test
    void protocol_advertises_academic_modality() {
        PubMedProtocol p = new PubMedProtocol(new ObjectMapper());
        assertThat(p.modalitiesSupported()).containsExactly(SearchModality.ACADEMIC);
        assertThat(p.id()).isEqualTo("pubmed");
    }

    @Test
    void instance_reports_ready_even_with_blank_baseurl() {
        ProviderInstanceConfig blank = new ProviderInstanceConfig(
                "pubmed", "pubmed", "", "", Map.of());
        PubMedProtocol p = new PubMedProtocol(new ObjectMapper(), mock(SimpleHttpClient.class));
        assertThat(p.instantiate(blank).availability(SCOPE))
                .isEqualTo(de.mhus.vance.toolpack.research.ProviderAvailability.READY);
    }

    @Test
    void search_parses_two_step_flow_into_hits() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, ESEARCH_JSON))
                .thenReturn(new Response(200, ESUMMARY_JSON));

        PubMedProtocol p = new PubMedProtocol(new ObjectMapper(), http);
        SearchResult r = p.instantiate(CFG).search(
                SearchRequest.normal("crispr crops", SearchModality.ACADEMIC, 5), SCOPE);

        assertThat(r.ok()).isTrue();
        assertThat(r.hits()).hasSize(2);

        SearchHit first = r.hits().get(0);
        assertThat(first.title()).isEqualTo("CRISPR-based crop improvement.");
        assertThat(first.url()).isEqualTo("https://pubmed.ncbi.nlm.nih.gov/42359401/");
        assertThat(first.source()).isEqualTo("PubMed");
        assertThat(first.modality()).isEqualTo(SearchModality.ACADEMIC);
        assertThat(first.snippet())
                .contains("Gain H")
                .contains("2026")
                .contains("Front Plant Sci");
        assertThat(first.extras())
                .containsEntry("pmid", "42359401")
                .containsEntry("doi", "10.3389/fpls.2026.1864496")
                .containsEntry("pmcid", "PMC13290587")
                .containsEntry("publicationYear", 2026)
                .containsEntry("venue", "Front Plant Sci")
                .containsEntry("volume", "17");
        assertThat(first.content()).isNull(); // no abstract in v1

        SearchHit second = r.hits().get(1);
        // authors > 3 → truncated with "et al."
        assertThat(second.snippet()).contains("Doe A, Roe B, Foo C, et al.");
        assertThat(second.extras())
                .containsEntry("pmid", "42358844")
                .doesNotContainKey("doi");
    }

    @Test
    void search_skips_esummary_when_esearch_returns_empty() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, """
                        {"esearchresult":{"count":"0","idlist":[]}}
                        """));

        PubMedProtocol p = new PubMedProtocol(new ObjectMapper(), http);
        SearchResult r = p.instantiate(CFG).search(
                SearchRequest.normal("xyzzy", SearchModality.ACADEMIC, 5), SCOPE);

        assertThat(r.ok()).isTrue();
        assertThat(r.hits()).isEmpty();
        // Exactly one HTTP call — no esummary.
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(1))
                .get(any(URI.class), any(String.class), any(Duration.class));
    }

    @Test
    void search_attaches_email_tool_and_apikey_params() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, """
                        {"esearchresult":{"idlist":["1"]}}"""))
                .thenReturn(new Response(200, """
                        {"result":{"uids":["1"],"1":{"uid":"1","title":"t",
                         "pubdate":"2024","source":"J","authors":[],
                         "articleids":[{"idtype":"pubmed","value":"1"}]}}}"""));

        PubMedProtocol p = new PubMedProtocol(new ObjectMapper(), http);
        p.instantiate(CFG_WITH_KEY).search(
                SearchRequest.normal("q", SearchModality.ACADEMIC, 1), SCOPE);

        ArgumentCaptor<URI> cap = ArgumentCaptor.forClass(URI.class);
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(2))
                .get(cap.capture(), any(), any());
        String esearchUrl = cap.getAllValues().get(0).toString();
        assertThat(esearchUrl)
                .contains("/esearch.fcgi")
                .contains("tool=Vance")
                .contains("email=me%40x.de")
                .contains("api_key=abc123");
        String esummaryUrl = cap.getAllValues().get(1).toString();
        assertThat(esummaryUrl)
                .contains("/esummary.fcgi")
                .contains("id=1")
                .contains("api_key=abc123");
    }

    @Test
    void search_returns_soft_failure_for_non_academic_modality() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        PubMedProtocol p = new PubMedProtocol(new ObjectMapper(), http);
        SearchResult r = p.instantiate(CFG).search(
                SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMessage()).contains("WEB").contains("not supported");
        org.mockito.Mockito.verifyNoInteractions(http);
    }
}
