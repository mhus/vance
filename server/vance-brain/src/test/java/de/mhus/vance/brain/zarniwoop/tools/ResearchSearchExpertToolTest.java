package de.mhus.vance.brain.zarniwoop.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.zarniwoop.ZarniwoopService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResearchSearchExpertToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "alpha", null, null, null);

    private ZarniwoopService service;
    private ResearchSearchExpertTool tool;

    @BeforeEach
    void setUp() {
        service = mock(ZarniwoopService.class);
        tool = new ResearchSearchExpertTool(service);
        when(service.search(any(SearchRequest.class), any(SearchScope.class), any()))
                .thenReturn(new SearchResult(
                        "q", SearchModality.WEB, "ode-news", SearchTier.EXPERT,
                        List.of(), 0, 0, null, null, Map.of()));
    }

    private SearchRequest capturedRequest(Map<String, Object> params) {
        tool.invoke(params, CTX);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(service)
                .search(captor.capture(), any(SearchScope.class), any());
        return captor.getValue();
    }

    @Test
    void invoke_forwardsEndpointDeclaredParams_notOnlyTheNamedFive() {
        SearchRequest req = capturedRequest(Map.of(
                "query", "tariffs",
                "modality", "news",
                "params", Map.of("originPlace", "m49:142")));

        assertThat(req.expertParams()).containsEntry("originPlace", "m49:142");
    }

    @Test
    void invoke_keepsNamedFiltersAuthoritativeOverTheGenericMap() {
        SearchRequest req = capturedRequest(Map.of(
                "query", "tariffs",
                "modality", "web",
                "site", "arxiv.org",
                "params", Map.of("site", "example.com")));

        assertThat(req.expertParams()).containsEntry("site", "arxiv.org");
    }

    @Test
    void invoke_carriesNumbersAndBooleansButDropsNestedValues() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("radiusKm", 50);
        nested.put("includeArchived", true);
        nested.put("bbox", List.of(1, 2, 3, 4));

        SearchRequest req = capturedRequest(Map.of(
                "query", "quakes", "modality", "news", "params", nested));

        assertThat(req.expertParams())
                .containsEntry("radiusKm", 50)
                .containsEntry("includeArchived", true)
                .doesNotContainKey("bbox");
    }

    @Test
    void invoke_ignoresAMalformedParamsBlock() {
        SearchRequest req = capturedRequest(Map.of(
                "query", "tariffs", "modality", "news", "params", "originPlace=m49:142"));

        assertThat(req.expertParams()).isEmpty();
    }

    @Test
    void schema_offersTheGenericParamsObject() {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) tool.paramsSchema().get("properties");

        assertThat(properties).containsKey("params");
    }
}
