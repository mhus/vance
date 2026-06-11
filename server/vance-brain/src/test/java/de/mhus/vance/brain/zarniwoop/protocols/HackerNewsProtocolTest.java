package de.mhus.vance.brain.zarniwoop.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

class HackerNewsProtocolTest {

    private static final SearchScope SCOPE = SearchScope.of("acme", "alpha");
    private static final ProviderInstanceConfig CFG = new ProviderInstanceConfig(
            "hn-algolia", "hackernews",
            "https://hn.algolia.com/api/v1",
            "", Map.of());

    @Test
    void protocol_advertises_news_and_web() {
        HackerNewsProtocol p = new HackerNewsProtocol(new ObjectMapper(), mock(SimpleHttpClient.class));
        assertThat(p.modalitiesSupported())
                .containsExactlyInAnyOrder(SearchModality.NEWS, SearchModality.WEB);
    }

    @Test
    void search_parses_algolia_hits() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, """
                    {"hits":[
                       {"title":"Show HN: My new lang","url":"https://example.com/lang",
                        "objectID":"1234","author":"alice","points":42,"num_comments":17,
                        "created_at":"2026-06-10T10:00:00Z"},
                       {"title":"Ask HN: foo?","url":"","objectID":"5678",
                        "author":"bob","points":3}
                    ]}"""));

        HackerNewsProtocol protocol = new HackerNewsProtocol(new ObjectMapper(), http);
        SearchResult result = protocol.instantiate(CFG)
                .search(SearchRequest.normal("lang", SearchModality.NEWS, 5), SCOPE);

        assertThat(result.ok()).isTrue();
        assertThat(result.hits()).hasSize(2);
        SearchHit first = result.hits().get(0);
        assertThat(first.title()).isEqualTo("Show HN: My new lang");
        assertThat(first.url()).isEqualTo("https://example.com/lang");
        assertThat(first.extras())
                .containsEntry("author", "alice")
                .containsEntry("points", 42)
                .containsEntry("comments", 17)
                .containsEntry("hnDiscussion", "https://news.ycombinator.com/item?id=1234");
        // Ask-HN posts without external URL link back to the HN item page.
        SearchHit askHn = result.hits().get(1);
        assertThat(askHn.url()).isEqualTo("https://news.ycombinator.com/item?id=5678");
    }

    @Test
    void search_sends_query_and_story_tag() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, "{\"hits\":[]}"));
        HackerNewsProtocol protocol = new HackerNewsProtocol(new ObjectMapper(), http);
        protocol.instantiate(CFG)
                .search(SearchRequest.normal("react server components", SearchModality.NEWS, 7), SCOPE);

        ArgumentCaptor<URI> cap = ArgumentCaptor.forClass(URI.class);
        org.mockito.Mockito.verify(http).get(cap.capture(), any(), any());
        String url = cap.getValue().toString();
        assertThat(url).contains("query=react+server+components");
        assertThat(url).contains("tags=story");
        assertThat(url).contains("hitsPerPage=7");
    }

    @Test
    void search_throws_on_non_200() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(503, "down"));
        HackerNewsProtocol protocol = new HackerNewsProtocol(new ObjectMapper(), http);
        assertThatThrownBy(() ->
                protocol.instantiate(CFG)
                        .search(SearchRequest.normal("q", SearchModality.NEWS, 5), SCOPE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 503");
    }
}
