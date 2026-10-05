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
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class SearXNGProtocolTest {

    private static final SearchScope SCOPE = SearchScope.of("acme", "alpha");
    private static final ProviderInstanceConfig CFG =
            new ProviderInstanceConfig("home-searxng", "searxng", "http://searxng:8080", "", Map.of());

    @Test
    void search_parses_results_and_sends_the_category() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        ArgumentCaptor<URI> url = ArgumentCaptor.forClass(URI.class);
        when(http.get(url.capture(), any(), any(Duration.class))).thenReturn(new JsonResponse(200, """
                    {"results":[
                       {"title":"LangGraph4j","url":"https://langgraph4j.github.io",
                        "content":"Java graph engineering for agents",
                        "publishedDate":"2026-05-01T00:00:00Z",
                        "img_src":"https://example.org/t.png","engine":"google",
                        "category":"general"},
                       {"title":"","url":"https://example.org/no-title"}
                    ]}""", Map.of()));

        SearXNGProtocol protocol = new SearXNGProtocol(new ObjectMapper(), http);
        SearchResult result =
                protocol.instantiate(CFG).search(SearchRequest.normal("langgraph4j", SearchModality.WEB, 5), SCOPE);

        assertThat(result.ok()).isTrue();
        assertThat(result.hits()).hasSize(2);
        SearchHit first = result.hits().get(0);
        assertThat(first.title()).isEqualTo("LangGraph4j");
        assertThat(first.snippet()).isEqualTo("Java graph engineering for agents");
        assertThat(first.source()).isEqualTo("google");
        assertThat(first.extras())
                .containsEntry("imageUrl", "https://example.org/t.png")
                .containsEntry("publishedDate", "2026-05-01T00:00:00Z");
        // A SearXNG result *is* its snippet — no separate body channel.
        assertThat(first.content()).isNull();
        // Title-less hits fall back to the url (the record requires one).
        assertThat(result.hits().get(1).title()).isEqualTo("https://example.org/no-title");
        assertThat(url.getValue().toString()).contains("categories=general").contains("format=json");
    }

    @Test
    void search_maps_modality_to_searxng_category() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        ArgumentCaptor<URI> url = ArgumentCaptor.forClass(URI.class);
        when(http.get(url.capture(), any(), any(Duration.class)))
                .thenReturn(new JsonResponse(200, "{\"results\":[]}", Map.of()));

        SearXNGProtocol protocol = new SearXNGProtocol(new ObjectMapper(), http);
        SearchResult result =
                protocol.instantiate(CFG).search(SearchRequest.normal("fusion", SearchModality.NEWS, 5), SCOPE);

        assertThat(result.ok()).isTrue();
        assertThat(url.getValue().toString()).contains("categories=news");
    }

    @Test
    void search_html_answer_is_a_soft_failure_not_a_cooldown() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        when(http.get(any(URI.class), any(), any(Duration.class)))
                .thenReturn(new JsonResponse(200, "<html><body>searxng</body></html>", Map.of()));

        SearXNGProtocol protocol = new SearXNGProtocol(new ObjectMapper(), http);
        SearchResult result = protocol.instantiate(CFG).search(SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("does not serve JSON");
    }

    @Test
    void search_rate_limit_is_a_typed_error_carrying_retry_after() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        when(http.get(any(URI.class), any(), any(Duration.class)))
                .thenReturn(new JsonResponse(429, "slow down", Map.of("retry-after", "7")));

        SearXNGProtocol protocol = new SearXNGProtocol(new ObjectMapper(), http);

        assertThatThrownBy(
                        () -> protocol.instantiate(CFG).search(SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE))
                .isInstanceOf(SearchProviderHttpException.class)
                .satisfies(t -> {
                    SearchProviderHttpException e = (SearchProviderHttpException) t;
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.retryAfter()).isNotNull();
                });
    }

    @Test
    void unsupported_modality_is_a_soft_failure() {
        SearXNGProtocol protocol = new SearXNGProtocol(new ObjectMapper(), mock(JsonHttpClient.class));
        SearchResult result =
                protocol.instantiate(CFG).search(SearchRequest.normal("q", SearchModality.ENCYCLOPEDIA, 5), SCOPE);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("does not serve modality");
    }

    @Test
    void availability_needs_an_instance_url() {
        SearXNGProtocol protocol = new SearXNGProtocol(new ObjectMapper(), mock(JsonHttpClient.class));
        assertThat(protocol.instantiate(CFG).availability(SCOPE)).isEqualTo(ProviderAvailability.READY);
        assertThat(protocol.instantiate(new ProviderInstanceConfig("no-url", "searxng", "", "", Map.of()))
                        .availability(SCOPE))
                .isEqualTo(ProviderAvailability.DISABLED);
    }
}
