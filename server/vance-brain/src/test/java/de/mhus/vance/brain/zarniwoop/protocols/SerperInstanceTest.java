package de.mhus.vance.brain.zarniwoop.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.web.ImageValidatorService;
import de.mhus.vance.brain.tools.web.YouTubeValidatorService;
import de.mhus.vance.brain.zarniwoop.protocols.SerperHttpClient.SerperResponse;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
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

class SerperInstanceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "alpha";
    private static final SearchScope SCOPE = SearchScope.of(TENANT, PROJECT);

    private static final String CREDENTIAL_LOCATION =
            "_vance/config/research/serper-main.yaml#apiKey";

    /** The credential now arrives with the config; the factory resolved it. */
    private SerperInstance newInstance(
            @org.jspecify.annotations.Nullable String credential, SerperHttpClient http) {
        ProviderInstanceConfig cfg = new ProviderInstanceConfig(
                "serper-main", "serper",
                "https://google.serper.dev",
                CREDENTIAL_LOCATION,
                () -> credential,
                Map.of(), TENANT, PROJECT);
        return new SerperInstance(
                cfg, new ObjectMapper(), http,
                mock(ImageValidatorService.class),
                mock(YouTubeValidatorService.class),
                mock(SerperPdfHeadProbe.class));
    }

    @Test
    void availability_NO_CREDENTIALS_when_noKeyIsConfigured() {
        String credential = null;

        SerperInstance instance = newInstance(credential, mock(SerperHttpClient.class));

        assertThat(instance.availability(SCOPE)).isEqualTo(ProviderAvailability.NO_CREDENTIALS);
    }

    @Test
    void availability_READY_when_instance_key_set() {
        String credential = "KEY-NEW";

        SerperInstance instance = newInstance(credential, mock(SerperHttpClient.class));

        assertThat(instance.availability(SCOPE)).isEqualTo(ProviderAvailability.READY);
        assertThat(instance.resolveApiKey(SCOPE)).isEqualTo("KEY-NEW");
    }

    @Test
    void search_parses_organic_hits_from_serper_response() throws Exception {
        String credential = "KEY";
        SerperHttpClient http = mock(SerperHttpClient.class);
        String body = """
                {
                  "organic": [
                    {"title": "First", "link": "https://a/", "snippet": "snip A",
                     "position": 1, "source": "a.com"},
                    {"title": "Second", "link": "https://b/", "snippet": "snip B",
                     "position": 2}
                  ]
                }
                """;
        when(http.post(any(URI.class), eq("KEY"), any(String.class), any(Duration.class)))
                .thenReturn(new SerperResponse(200, body,
                        Map.of("x-ratelimit-remaining", "24")));

        SerperInstance instance = newInstance(credential, http);

        SearchResult result = instance.search(
                SearchRequest.normal("topic", SearchModality.WEB, 5), SCOPE);

        assertThat(result.ok()).isTrue();
        assertThat(result.providerInstanceId()).isEqualTo("serper-main");
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().get(0).title()).isEqualTo("First");
        assertThat(result.hits().get(0).extras()).containsEntry("position", 1);
        assertThat(result.upstreamHeaders()).containsEntry("x-ratelimit-remaining", "24");
    }

    @Test
    void search_sends_query_and_clamped_num_in_request_body() throws Exception {
        String credential = "KEY";
        SerperHttpClient http = mock(SerperHttpClient.class);
        when(http.post(any(URI.class), eq("KEY"), any(String.class), any(Duration.class)))
                .thenReturn(new SerperResponse(200, "{\"organic\":[]}", Map.of()));

        SerperInstance instance = newInstance(credential, http);
        instance.search(SearchRequest.normal("Lissabon", SearchModality.WEB, 25), SCOPE);

        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<URI> urlCap = ArgumentCaptor.forClass(URI.class);
        org.mockito.Mockito.verify(http).post(urlCap.capture(), eq("KEY"),
                bodyCap.capture(), any(Duration.class));
        assertThat(urlCap.getValue().toString())
                .isEqualTo("https://google.serper.dev/search");
        assertThat(bodyCap.getValue()).contains("\"q\":\"Lissabon\"");
        // num was clamped from 25 down to MAX_NUM (10).
        assertThat(bodyCap.getValue()).contains("\"num\":10");
    }

    // ── NEWS ──────────────────────────────────────────────────────────

    @Test
    void newsSearch_hitsTheNewsIndexAndCarriesOutletDateAndLeadImage() throws Exception {
        String credential = "KEY";
        SerperHttpClient http = mock(SerperHttpClient.class);
        String body = """
                {
                  "news": [
                    {"title": "Quake hits Amatrice", "link": "https://bbc/1",
                     "snippet": "At least 159 dead", "date": "2 hours ago",
                     "source": "BBC", "imageUrl": "https://bbc/lead.jpg",
                     "position": 1}
                  ]
                }
                """;
        when(http.post(any(URI.class), eq("KEY"), any(String.class), any(Duration.class)))
                .thenReturn(new SerperResponse(200, body, Map.of()));

        SerperInstance instance = newInstance(credential, http);
        SearchResult result = instance.search(
                SearchRequest.normal("amatrice", SearchModality.NEWS, 5), SCOPE);

        ArgumentCaptor<URI> urlCap = ArgumentCaptor.forClass(URI.class);
        org.mockito.Mockito.verify(http).post(urlCap.capture(), eq("KEY"),
                any(String.class), any(Duration.class));
        assertThat(urlCap.getValue().toString()).isEqualTo("https://google.serper.dev/news");

        assertThat(result.hits()).hasSize(1);
        SearchHit hit = result.hits().getFirst();
        assertThat(hit.modality()).isEqualTo(SearchModality.NEWS);
        assertThat(hit.source()).isEqualTo("BBC");
        assertThat(hit.extras()).containsEntry("date", "2 hours ago");
    }

    /**
     * The lead image is a preview, not the thing found. Under
     * {@code imageUrl} a reader would be offered "open image file" for an
     * article — see {@code doNewsSearch}.
     */
    @Test
    void newsLeadImageTravelsAsThumbnailNotAsTheResultItself() throws Exception {
        String credential = "KEY";
        SerperHttpClient http = mock(SerperHttpClient.class);
        when(http.post(any(URI.class), eq("KEY"), any(String.class), any(Duration.class)))
                .thenReturn(new SerperResponse(200, """
                        {"news": [{"title": "T", "link": "https://x/",
                                   "imageUrl": "https://x/lead.jpg"}]}
                        """, Map.of()));

        SerperInstance instance = newInstance(credential, http);
        SearchResult result = instance.search(
                SearchRequest.normal("q", SearchModality.NEWS, 5), SCOPE);

        assertThat(result.hits().getFirst().extras())
                .containsEntry("thumbnailUrl", "https://x/lead.jpg")
                .doesNotContainKey("imageUrl");
    }

    @Test
    void newsRowWithoutALinkIsSkippedRatherThanRenderedAsDeadText() throws Exception {
        String credential = "KEY";
        SerperHttpClient http = mock(SerperHttpClient.class);
        when(http.post(any(URI.class), eq("KEY"), any(String.class), any(Duration.class)))
                .thenReturn(new SerperResponse(200, """
                        {"news": [{"title": "No link here"},
                                  {"title": "Fine", "link": "https://ok/"}]}
                        """, Map.of()));

        SerperInstance instance = newInstance(credential, http);
        SearchResult result = instance.search(
                SearchRequest.normal("q", SearchModality.NEWS, 5), SCOPE);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst().title()).isEqualTo("Fine");
    }

    @Test
    void newsIsDeclaredSoTheDispatcherRoutesTheModalityHere() {
        SerperInstance instance = newInstance("KEY", mock(SerperHttpClient.class));

        assertThat(instance.modalities()).contains(SearchModality.NEWS);
    }

    @Test
    void search_returns_soft_failure_when_no_key() {
        String credential = null;

        SerperInstance instance = newInstance(credential, mock(SerperHttpClient.class));

        SearchResult result = instance.search(
                SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE);
        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("API key not configured");
    }

    @Test
    void search_throws_on_non_200_for_agrajag_classification() throws Exception {
        String credential = "KEY";
        SerperHttpClient http = mock(SerperHttpClient.class);
        when(http.post(any(URI.class), eq("KEY"), any(String.class), any(Duration.class)))
                .thenReturn(new SerperResponse(429, "{\"error\":\"rate limited\"}",
                        Map.of("retry-after", "30")));

        SerperInstance instance = newInstance(credential, http);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                instance.search(SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 429");
    }

    @Test
    void currentQuota_reads_balance_from_account_endpoint() throws Exception {
        String credential = "KEY";
        SerperHttpClient http = mock(SerperHttpClient.class);
        when(http.get(any(URI.class), eq("KEY"), any(Duration.class)))
                .thenReturn(new SerperResponse(200,
                        "{\"balance\":1552,\"rateLimit\":5}", Map.of()));

        SerperInstance instance = newInstance(credential, http);

        Optional<QuotaStatus> q = instance.currentQuota(SCOPE);

        assertThat(q).isPresent();
        assertThat(q.get().remaining()).isEqualTo(1552L);
    }

    @Test
    void currentQuota_empty_when_no_key() {
        String credential = null;

        SerperInstance instance = newInstance(credential, mock(SerperHttpClient.class));

        assertThat(instance.currentQuota(SCOPE)).isEmpty();
    }

    @Test
    void currentQuota_empty_when_account_returns_non_200() throws Exception {
        String credential = "KEY";
        SerperHttpClient http = mock(SerperHttpClient.class);
        when(http.get(any(URI.class), eq("KEY"), any(Duration.class)))
                .thenReturn(new SerperResponse(403, "forbidden", Map.of()));

        SerperInstance instance = newInstance(credential, http);

        assertThat(instance.currentQuota(SCOPE)).isEmpty();
    }

}
