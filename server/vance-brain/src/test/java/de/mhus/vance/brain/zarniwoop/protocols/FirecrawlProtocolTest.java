package de.mhus.vance.brain.zarniwoop.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.zarniwoop.protocols.JsonHttpClient.JsonResponse;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchQuotaExceededException;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class FirecrawlProtocolTest {

    private static final SearchScope SCOPE = SearchScope.of("acme", "alpha");
    private static final ProviderInstanceConfig KEYED = new ProviderInstanceConfig(
            "firecrawl-main",
            "firecrawl",
            "https://api.firecrawl.dev",
            "_vance/config/research/firecrawl-main.yaml#apiKey",
            () -> "fc-key",
            Map.of(),
            "acme",
            "alpha");
    private static final ProviderInstanceConfig KEYLESS =
            new ProviderInstanceConfig("firecrawl-keyless", "firecrawl", "https://api.firecrawl.dev", "", Map.of());

    @Test
    void search_sends_sources_per_modality_and_parses_web_hits() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(java.util.Map.class);
        when(http.post(any(URI.class), headers.capture(), body.capture(), any(Duration.class)))
                .thenReturn(new JsonResponse(200, """
                    {"success":true,"data":{"web":[
                       {"url":"https://langgraph4j.github.io","title":"LangGraph4j",
                        "description":"Java graph engineering for agents",
                        "position":1,"category":"github"}
                    ]},"creditsUsed":2}""", Map.of()));

        FirecrawlProtocol protocol = new FirecrawlProtocol(new ObjectMapper(), http);
        SearchResult result =
                protocol.instantiate(KEYED).search(SearchRequest.normal("langgraph4j", SearchModality.WEB, 5), SCOPE);

        assertThat(result.ok()).isTrue();
        assertThat(result.hits()).hasSize(1);
        SearchHit hit = result.hits().get(0);
        assertThat(hit.title()).isEqualTo("LangGraph4j");
        assertThat(hit.source()).isEqualTo("Firecrawl");
        assertThat(hit.extras()).containsEntry("position", 1).containsEntry("category", "github");
        assertThat(hit.content()).isNotNull();
        assertThat(hit.content().inlineText()).isEqualTo("Java graph engineering for agents");

        assertThat(body.getValue()).contains("\"sources\":[\"web\"]");
        assertThat(headers.getValue()).containsEntry("Authorization", "Bearer fc-key");
    }

    @Test
    void image_search_returns_the_image_url_as_extra() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        when(http.post(any(URI.class), any(), any(String.class), any(Duration.class)))
                .thenReturn(new JsonResponse(200, """
                    {"success":true,"data":{"images":[
                       {"url":"https://en.wikipedia.org/wiki/Castle","title":"Castle",
                        "imageUrl":"https://img.example/castle.jpg",
                        "imageWidth":1280,"imageHeight":960,"position":1}
                    ]},"creditsUsed":2}""", Map.of()));

        FirecrawlProtocol protocol = new FirecrawlProtocol(new ObjectMapper(), http);
        SearchResult result =
                protocol.instantiate(KEYED).search(SearchRequest.normal("castle", SearchModality.IMAGE, 5), SCOPE);

        assertThat(result.hits()).hasSize(1);
        SearchHit hit = result.hits().get(0);
        // The page URL is the hit; the image itself is an extra (two links,
        // same as the search app renders them).
        assertThat(hit.url()).isEqualTo("https://en.wikipedia.org/wiki/Castle");
        assertThat(hit.extras())
                .containsEntry("imageUrl", "https://img.example/castle.jpg")
                .containsEntry("imageWidth", 1280)
                .containsEntry("imageHeight", 960);
    }

    @Test
    void payment_required_is_quota_exhaustion() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        when(http.post(any(URI.class), any(), any(String.class), any(Duration.class)))
                .thenReturn(new JsonResponse(
                        402, "{\"success\":false,\"error\":\"Payment Required: Insufficient credits\"}", Map.of()));

        FirecrawlProtocol protocol = new FirecrawlProtocol(new ObjectMapper(), http);

        assertThatThrownBy(() ->
                        protocol.instantiate(KEYED).search(SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE))
                .isInstanceOf(SearchQuotaExceededException.class)
                .hasMessageContaining("Insufficient credits");
    }

    @Test
    void currentQuota_reads_team_credit_usage() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        ArgumentCaptor<URI> url = ArgumentCaptor.forClass(URI.class);
        when(http.get(url.capture(), any(), any(Duration.class))).thenReturn(new JsonResponse(200, """
                    {"success":true,"data":{"remainingCredits":1000,
                     "planCredits":500000,
                     "billingPeriodStart":"2026-10-01T00:00:00Z",
                     "billingPeriodEnd":"2026-10-31T23:59:59Z"}}""", Map.of()));

        FirecrawlProtocol protocol = new FirecrawlProtocol(new ObjectMapper(), http);
        Optional<QuotaStatus> quota = protocol.instantiate(KEYED).currentQuota(SCOPE);

        assertThat(quota).isPresent();
        assertThat(quota.get().remaining()).isEqualTo(1000);
        assertThat(quota.get().limit()).isEqualTo(500000);
        assertThat(quota.get().resetsAt()).isNotNull();
        assertThat(url.getValue().toString()).endsWith("/v2/team/credit-usage");
    }

    @Test
    void keyless_instances_send_no_auth_header_and_have_no_quota_probe() throws Exception {
        JsonHttpClient http = mock(JsonHttpClient.class);
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(java.util.Map.class);
        when(http.post(any(URI.class), headers.capture(), any(String.class), any(Duration.class)))
                .thenReturn(new JsonResponse(200, "{\"success\":true,\"data\":{\"web\":[]}}", Map.of()));

        FirecrawlProtocol protocol = new FirecrawlProtocol(new ObjectMapper(), http);
        SearchResult result =
                protocol.instantiate(KEYLESS).search(SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE);

        assertThat(result.ok()).isTrue();
        assertThat(headers.getValue()).doesNotContainKey("Authorization");
        // No account → nothing to ask about; the per-IP limit surfaces as 429.
        assertThat(protocol.instantiate(KEYLESS).currentQuota(SCOPE)).isEmpty();
        assertThat(protocol.instantiate(KEYLESS).statusText(SCOPE)).contains("keyless");
    }
}
