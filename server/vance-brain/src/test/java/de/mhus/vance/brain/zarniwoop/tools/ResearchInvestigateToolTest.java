package de.mhus.vance.brain.zarniwoop.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.zarniwoop.ZarniwoopException;
import de.mhus.vance.brain.zarniwoop.ZarniwoopResearchService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.DroppedHit;
import de.mhus.vance.toolpack.research.RankedHit;
import de.mhus.vance.toolpack.research.RankedHitSet;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResearchInvestigateToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "alpha", null, null, null);

    private ZarniwoopResearchService service;
    private ResearchInvestigateTool tool;

    @BeforeEach
    void setUp() {
        service = mock(ZarniwoopResearchService.class);
        tool = new ResearchInvestigateTool(service);
    }

    @Test
    void tool_is_primary_read_only_with_required_question() {
        assertThat(tool.name()).isEqualTo("research_investigate");
        assertThat(tool.primary()).isTrue();
        assertThat(tool.labels()).contains("read-only");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) tool.paramsSchema().get("required");
        assertThat(required).containsExactly("question");
    }

    @Test
    void invoke_rejects_missing_question() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void invoke_rejects_blank_question() {
        assertThatThrownBy(() -> tool.invoke(Map.of("question", "  "), CTX))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void invoke_rejects_missing_project_scope() {
        ToolInvocationContext noProject =
                new ToolInvocationContext("acme", null, null, null, null);
        assertThatThrownBy(() ->
                tool.invoke(Map.of("question", "topic"), noProject))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("project");
    }

    @Test
    void invoke_translates_ZarniwoopException_to_ToolException() {
        when(service.investigate(eq("topic"), any(SearchScope.class), eq(CTX)))
                .thenThrow(new ZarniwoopException("plan blew up"));

        assertThatThrownBy(() -> tool.invoke(Map.of("question", "topic"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("plan blew up");
    }

    @Test
    void invoke_shapes_result_into_llm_friendly_map() {
        RankedHit kept = new RankedHit(
                "Some Title", "https://example/",
                0.72, 0.8, 0.9,
                SearchModality.WEB, "serper-main",
                "snippet", "ranked one", Map.of("position", 1));
        DroppedHit dropped = new DroppedHit(
                "Off Topic", "https://off/",
                SearchModality.WEB, "serper-main", "irrelevant");
        RankedHitSet hitSet = new RankedHitSet(
                "topic",
                List.of(kept),
                List.of(dropped),
                /* refineDepth */ 0,
                Set.of("serper-main"),
                Map.of("modality:web", 0.9),
                List.of("no academic coverage"));
        when(service.investigate(eq("topic"), any(SearchScope.class), eq(CTX)))
                .thenReturn(hitSet);

        Map<String, Object> out = tool.invoke(Map.of("question", "topic"), CTX);

        assertThat(out).containsEntry("question", "topic");
        assertThat(out).containsEntry("count", 1);
        assertThat(out).containsEntry("droppedCount", 1);
        @SuppressWarnings("unchecked")
        List<String> instancesUsed = (List<String>) out.get("instancesUsed");
        assertThat(instancesUsed).containsExactly("serper-main");
        @SuppressWarnings("unchecked")
        Map<String, Double> affinity = (Map<String, Double>) out.get("sourceAffinity");
        assertThat(affinity).containsEntry("modality:web", 0.9);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0))
                .containsEntry("title", "Some Title")
                .containsEntry("url", "https://example/")
                .containsEntry("finalScore", 0.72)
                .containsEntry("position", 1)
                .containsEntry("relevanceNote", "ranked one");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> droppedRows = (List<Map<String, Object>>) out.get("dropped");
        assertThat(droppedRows).hasSize(1);
        assertThat(droppedRows.get(0)).containsEntry("dropReason", "irrelevant");

        @SuppressWarnings("unchecked")
        List<String> gaps = (List<String>) out.get("gaps");
        assertThat(gaps).containsExactly("no academic coverage");
    }
}
