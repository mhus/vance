package de.mhus.vance.brain.zarniwoop.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.zarniwoop.ZarniwoopException;
import de.mhus.vance.brain.zarniwoop.ZarniwoopService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResearchSearchToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "alpha", null, null, null);

    private ZarniwoopService service;
    private ResearchSearchTool tool;

    @BeforeEach
    void setUp() {
        service = mock(ZarniwoopService.class);
        tool = new ResearchSearchTool(service);
    }

    @Test
    void tool_is_primary_read_only_with_required_query() {
        assertThat(tool.name()).isEqualTo("research_search");
        assertThat(tool.primary()).isTrue();
        assertThat(tool.labels()).contains("read-only");
        Map<String, Object> schema = tool.paramsSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactly("query");
    }

    @Test
    void invoke_rejects_missing_query() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("query");
    }

    @Test
    void invoke_rejects_blank_query() {
        assertThatThrownBy(() -> tool.invoke(Map.of("query", "   "), CTX))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void invoke_rejects_missing_project_scope() {
        ToolInvocationContext noProject =
                new ToolInvocationContext("acme", null, null, null, null);
        assertThatThrownBy(() -> tool.invoke(Map.of("query", "topic"), noProject))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("project");
    }

    @Test
    void invoke_defaults_modality_to_web_and_num_to_5() {
        SearchResult result = okResult("topic", SearchModality.WEB, "serper-main");
        when(service.search(any(SearchRequest.class), any(SearchScope.class), eq(CTX)))
                .thenReturn(result);

        tool.invoke(Map.of("query", "topic"), CTX);

        ArgumentCaptor<SearchRequest> reqCap = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(service)
                .search(reqCap.capture(), any(SearchScope.class), eq(CTX));
        SearchRequest req = reqCap.getValue();
        assertThat(req.modality()).isEqualTo(SearchModality.WEB);
        assertThat(req.tier()).isEqualTo(SearchTier.NORMAL);
        assertThat(req.maxResults()).isEqualTo(5);
    }

    @Test
    void invoke_clamps_num_to_max_10() {
        when(service.search(any(), any(), eq(CTX)))
                .thenReturn(okResult("topic", SearchModality.WEB, "serper-main"));

        tool.invoke(Map.of("query", "topic", "num", 999), CTX);

        ArgumentCaptor<SearchRequest> reqCap = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(service)
                .search(reqCap.capture(), any(), eq(CTX));
        assertThat(reqCap.getValue().maxResults()).isEqualTo(10);
    }

    @Test
    void invoke_accepts_modality_string_lowercase() {
        when(service.search(any(), any(), eq(CTX)))
                .thenReturn(okResult("topic", SearchModality.IMAGE, "serper-main"));

        tool.invoke(Map.of("query", "topic", "modality", "image"), CTX);

        ArgumentCaptor<SearchRequest> reqCap = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(service)
                .search(reqCap.capture(), any(), eq(CTX));
        assertThat(reqCap.getValue().modality()).isEqualTo(SearchModality.IMAGE);
    }

    @Test
    void invoke_rejects_unknown_modality_string() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("query", "topic", "modality", "spaceships"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Unknown modality");
    }

    @Test
    void invoke_translates_zarniwoop_exception_to_tool_exception() {
        when(service.search(any(), any(), eq(CTX)))
                .thenThrow(new ZarniwoopException("scope problem"));

        assertThatThrownBy(() -> tool.invoke(Map.of("query", "topic"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("scope problem");
    }

    @Test
    void invoke_shapes_result_into_llm_friendly_map() {
        when(service.search(any(), any(), eq(CTX)))
                .thenReturn(okResult("topic", SearchModality.WEB, "serper-main"));

        Map<String, Object> out = tool.invoke(Map.of("query", "topic"), CTX);

        assertThat(out).containsEntry("query", "topic");
        assertThat(out).containsEntry("modality", "web");
        assertThat(out).containsEntry("providerInstanceId", "serper-main");
        assertThat(out).containsEntry("count", 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("results");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0)).containsEntry("title", "Some Title");
        assertThat(hits.get(0)).containsEntry("url", "https://example/");
    }

    @Test
    void invoke_propagates_soft_failure_error_field() {
        SearchResult err = new SearchResult(
                "topic", SearchModality.WEB, "(none)", SearchTier.NORMAL,
                List.of(), 0, 0, null, "no provider available", Map.of());
        when(service.search(any(), any(), eq(CTX))).thenReturn(err);

        Map<String, Object> out = tool.invoke(Map.of("query", "topic"), CTX);

        assertThat(out).containsEntry("error", "no provider available");
        @SuppressWarnings("unchecked")
        List<?> hits = (List<?>) out.get("results");
        assertThat(hits).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static SearchResult okResult(String query, SearchModality modality,
                                          String instanceId) {
        return new SearchResult(
                query, modality, instanceId, SearchTier.NORMAL,
                List.of(new SearchHit(
                        "Some Title", "https://example/", "snippet",
                        "example", modality, null, Map.of())),
                1, 0, null, null, Map.of());
    }
}
