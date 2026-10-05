package de.mhus.vance.brain.zarniwoop.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.zarniwoop.protocols.JsonHttpClient.JsonResponse;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderHttpException;
import de.mhus.vance.toolpack.research.SearchQuotaExceededException;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ExaProtocolTest {

    private static final SearchScope SCOPE = SearchScope.of("acme", "alpha");
    private static final ProviderInstanceConfig CFG = new ProviderInstanceConfig(
            "exa-main",
            "exa",
            "https://api.exa.ai",
            "_vance/config/research/exa-main.yaml#apiKey",
            () -> "sk-key",
            Map.of(),
            "acme",
            "alpha");

    @Test
    void search_sends_json_body_and_parses_hits() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(http.post(any(URI.class), any(), body.capture(), any(Duration.class)))
                .thenReturn(new JsonResponse(200, """
                    {"results":[
                       {"title":"LangGraph4j","url":"https://langgraph4j.github.io",
                        "publishedDate":"2026-05-01T00:00:00Z","author":"lsaint",
                        "image":"https://example.org/i.png",
                        "text":"LangGraph4j is a Java library for graphs.",
                        "highlights":["Java library for graphs"]},
                       {"title":"No url means no hit","url":""}
                    ],"costDollars":{"total":0.004}}""", Map.of()));

        ExaProtocol protocol = new ExaProtocol(new ObjectMapper(), http);
        SearchResult result =
                protocol.instantiate(CFG).search(SearchRequest.normal("langgraph4j", SearchModality.WEB, 5), SCOPE);

        assertThat(result.ok()).isTrue();
        assertThat(result.hits()).hasSize(1);
        SearchHit hit = result.hits().get(0);
        assertThat(hit.title()).isEqualTo("LangGraph4j");
        assertThat(hit.snippet()).isEqualTo("Java library for graphs");
        assertThat(hit.source()).isEqualTo("Exa");
        assertThat(hit.extras())
                .containsEntry("publishedDate", "2026-05-01T00:00:00Z")
                .containsEntry("author", "lsaint")
                .containsEntry("imageUrl", "https://example.org/i.png");
        // Body channel carries Exa's own text (capped downstream).
        assertThat(hit.content()).isNotNull();
        assertThat(hit.content().inlineText()).isEqualTo("LangGraph4j is a Java library for graphs.");

        assertThat(body.getValue())
                .contains("\"numResults\":5")
                .contains("\"type\":\"auto\"")
                .contains("\"highlights\":true");
        // WEB searches without a category.
        assertThat(body.getValue()).doesNotContain("\"category\"");
    }

    @Test
    void academic_modality_maps_to_the_publication_category() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(http.post(any(URI.class), any(), body.capture(), any(Duration.class)))
                .thenReturn(new JsonResponse(200, "{\"results\":[]}", Map.of()));

        ExaProtocol protocol = new ExaProtocol(new ObjectMapper(), http);
        protocol.instantiate(CFG).search(SearchRequest.normal("attention", SearchModality.ACADEMIC, 5), SCOPE);

        assertThat(body.getValue()).contains("\"category\":\"publication\"");
    }

    @Test
    void named_expert_filters_map_onto_exa_fields() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(http.post(any(URI.class), any(), body.capture(), any(Duration.class)))
                .thenReturn(new JsonResponse(200, "{\"results\":[]}", Map.of()));

        ExaProtocol protocol = new ExaProtocol(new ObjectMapper(), http);
        SearchRequest req = new SearchRequest(
                "q",
                SearchModality.WEB,
                de.mhus.vance.toolpack.research.SearchTier.EXPERT,
                5,
                null,
                null,
                Map.of(
                        "site", "arxiv.org",
                        "dateFrom", "2024-01-01",
                        "excludeDomains", List.of("example.com")));
        protocol.instantiate(CFG).search(req, SCOPE);

        assertThat(body.getValue())
                .contains("\"includeDomains\":[\"arxiv.org\"]")
                .contains("\"startPublishedDate\":\"2024-01-01\"")
                .contains("\"excludeDomains\":[\"example.com\"]");
    }

    @Test
    void payment_required_is_quota_exhaustion_not_a_broken_provider() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        when(http.post(any(URI.class), any(), any(String.class), any(Duration.class)))
                .thenReturn(new JsonResponse(402, "{\"error\":\"Insufficient credits\"}", Map.of()));

        ExaProtocol protocol = new ExaProtocol(new ObjectMapper(), http);

        assertThatThrownBy(
                        () -> protocol.instantiate(CFG).search(SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE))
                .isInstanceOf(SearchQuotaExceededException.class)
                .hasMessageContaining("returned HTTP 402");
    }

    @Test
    void rate_limit_carries_the_retry_after_hint() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        when(http.post(any(URI.class), any(), any(String.class), any(Duration.class)))
                .thenReturn(new JsonResponse(429, "{\"error\":\"Rate limit exceeded\"}", Map.of("retry-after", "30")));

        ExaProtocol protocol = new ExaProtocol(new ObjectMapper(), http);

        assertThatThrownBy(
                        () -> protocol.instantiate(CFG).search(SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE))
                .isInstanceOf(SearchProviderHttpException.class)
                .satisfies(t -> assertThat(((SearchProviderHttpException) t).retryAfter())
                        .isNotNull());
    }

    @Test
    void exa_rest_has_no_keyless_mode() {
        ExaProtocol protocol = new ExaProtocol(new ObjectMapper(), mock(JsonHttpClient.class));
        assertThat(protocol.instantiate(CFG).availability(SCOPE)).isEqualTo(ProviderAvailability.READY);
        assertThat(protocol.instantiate(
                                new ProviderInstanceConfig("exa-anon", "exa", "https://api.exa.ai", "", Map.of()))
                        .availability(SCOPE))
                .isEqualTo(ProviderAvailability.NO_CREDENTIALS);
    }
}
