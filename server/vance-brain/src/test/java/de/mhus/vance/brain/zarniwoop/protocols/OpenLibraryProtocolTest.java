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
import tools.jackson.databind.ObjectMapper;

class OpenLibraryProtocolTest {

    private static final SearchScope SCOPE = SearchScope.of("acme", "alpha");
    private static final ProviderInstanceConfig CFG = new ProviderInstanceConfig(
            "openlib", "openlibrary",
            "https://openlibrary.org",
            "", Map.of());

    @Test
    void protocol_advertises_book_modality() {
        OpenLibraryProtocol p = new OpenLibraryProtocol(new ObjectMapper(), mock(SimpleHttpClient.class));
        assertThat(p.modalitiesSupported()).containsExactly(SearchModality.BOOK);
    }

    @Test
    void search_parses_docs() throws Exception {
        SimpleHttpClient http = mock(SimpleHttpClient.class);
        when(http.get(any(URI.class), any(String.class), any(Duration.class)))
                .thenReturn(new Response(200, """
                    {"docs":[
                       {"title":"The Lord of the Rings",
                        "subtitle":"the Fellowship of the Ring",
                        "key":"/works/OL27448W",
                        "author_name":["J.R.R. Tolkien"],
                        "first_publish_year":1954,
                        "cover_i":7222252,
                        "isbn":["0395489326"],
                        "publisher":["Houghton Mifflin"],
                        "edition_count":175}
                    ]}"""));
        OpenLibraryProtocol p = new OpenLibraryProtocol(new ObjectMapper(), http);
        SearchResult r = p.instantiate(CFG)
                .search(SearchRequest.normal("Tolkien", SearchModality.BOOK, 5), SCOPE);

        assertThat(r.ok()).isTrue();
        assertThat(r.hits()).hasSize(1);
        SearchHit hit = r.hits().get(0);
        assertThat(hit.title()).isEqualTo("The Lord of the Rings");
        assertThat(hit.url()).isEqualTo("https://openlibrary.org/works/OL27448W");
        assertThat(hit.snippet()).contains("J.R.R. Tolkien").contains("1954");
        assertThat(hit.extras())
                .containsEntry("author", "J.R.R. Tolkien")
                .containsEntry("firstPublishYear", 1954)
                .containsEntry("isbn", "0395489326")
                .containsEntry("coverThumbnailUrl",
                        "https://covers.openlibrary.org/b/id/7222252-M.jpg")
                .containsEntry("editionCount", 175);
    }

    @Test
    void search_rejects_wrong_modality() {
        OpenLibraryProtocol p = new OpenLibraryProtocol(new ObjectMapper(), mock(SimpleHttpClient.class));
        SearchResult r = p.instantiate(CFG)
                .search(SearchRequest.normal("q", SearchModality.WEB, 5), SCOPE);
        assertThat(r.ok()).isFalse();
        assertThat(r.errorMessage()).contains("not supported");
    }
}
