package de.mhus.vance.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.RankedHitSet;
import de.mhus.vance.toolpack.research.SearchHit;
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

class ZarniwoopResearchServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "alpha";
    private static final SearchScope SCOPE = SearchScope.of(TENANT, PROJECT);
    private static final ToolInvocationContext CTX =
            new ToolInvocationContext(TENANT, PROJECT, null, null, null);

    private LightLlmService lightLlm;
    private ZarniwoopService zarniwoopService;
    private SearchProviderFactory factory;
    private ZarniwoopResearchService service;

    @BeforeEach
    void setUp() {
        lightLlm = mock(LightLlmService.class);
        zarniwoopService = mock(ZarniwoopService.class);
        factory = mock(SearchProviderFactory.class);
        when(factory.assemble(any())).thenReturn(List.of());
        service = new ZarniwoopResearchService(lightLlm, zarniwoopService, factory);
    }

    @Test
    void rejects_blank_question() {
        assertThatThrownBy(() -> service.investigate("  ", SCOPE, CTX))
                .isInstanceOf(ZarniwoopException.class);
    }

    @Test
    void rejects_missing_project() {
        SearchScope tenantOnly = new SearchScope(TENANT, "", null, null);
        assertThatThrownBy(() -> service.investigate("q", tenantOnly, CTX))
                .isInstanceOf(ZarniwoopException.class);
    }

    @Test
    void empty_plan_returns_empty_hit_set_without_searching() {
        stubPlan(Map.of("steps", List.of()));

        RankedHitSet result = service.investigate("a question", SCOPE, CTX);

        assertThat(result.keptHits()).isEmpty();
        assertThat(result.gaps()).hasSize(1);
        verify(zarniwoopService, never()).search(any(), any(), any());
    }

    @Test
    void plan_drives_search_requests_with_modality_and_pin() {
        stubPlan(Map.of("steps", List.of(
                Map.of("modality", "web", "query", "rewritten", "num", 4),
                Map.of("modality", "encyclopedia",
                        "query", "encyclopedia query",
                        "instance", "wiki-de", "num", 2))));
        when(zarniwoopService.search(any(SearchRequest.class), eq(SCOPE), eq(CTX)))
                .thenReturn(emptyResult(SearchModality.WEB));
        stubEvaluate(Map.of("verdicts", List.of()));

        service.investigate("topic", SCOPE, CTX);

        ArgumentCaptor<SearchRequest> reqCap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(zarniwoopService, org.mockito.Mockito.times(2))
                .search(reqCap.capture(), eq(SCOPE), eq(CTX));
        List<SearchRequest> reqs = reqCap.getAllValues();
        assertThat(reqs).extracting(SearchRequest::modality)
                .containsExactlyInAnyOrder(SearchModality.WEB, SearchModality.ENCYCLOPEDIA);
        SearchRequest pinned = reqs.stream()
                .filter(r -> r.modality() == SearchModality.ENCYCLOPEDIA)
                .findFirst().orElseThrow();
        assertThat(pinned.pinnedProviderId()).isEqualTo("wiki-de");
        assertThat(pinned.tier()).isEqualTo(SearchTier.EXPERT);
    }

    @Test
    void evaluate_keep_drop_split_lands_in_result() {
        stubPlan(Map.of(
                "steps", List.of(Map.of("modality", "web", "query", "x", "num", 5)),
                "sourceAffinity", Map.of("modality:web", 0.8)));
        SearchResult sr = oneResultSearchResult(SearchModality.WEB, "serper-main", List.of(
                hit("Alpha", "https://a/"),
                hit("Beta", "https://b/"),
                hit("Gamma", "https://c/")));
        when(zarniwoopService.search(any(), eq(SCOPE), eq(CTX))).thenReturn(sr);

        stubEvaluate(Map.of("verdicts", List.of(
                Map.of("hitId", "h0", "verdict", "keep",
                        "relevanceScore", 0.9, "relevanceNote", "rank one"),
                Map.of("hitId", "h1", "verdict", "drop",
                        "relevanceScore", 0.1, "dropReason", "off-topic"),
                Map.of("hitId", "h2", "verdict", "keep",
                        "relevanceScore", 0.4))));

        RankedHitSet result = service.investigate("topic", SCOPE, CTX);

        assertThat(result.keptHits()).hasSize(2);
        assertThat(result.droppedHits()).hasSize(1);
        assertThat(result.keptHits().get(0).title()).isEqualTo("Alpha");
        assertThat(result.keptHits().get(0).finalScore()).isEqualTo(0.9 * 0.8);
        assertThat(result.keptHits().get(0).sourceAffinityApplied()).isEqualTo(0.8);
        assertThat(result.keptHits().get(1).title()).isEqualTo("Gamma");
        assertThat(result.droppedHits().get(0).dropReason()).isEqualTo("off-topic");
        assertThat(result.instancesUsed()).containsExactly("serper-main");
    }

    @Test
    void source_affinity_keys_can_pin_specific_instance() {
        stubPlan(Map.of(
                "steps", List.of(Map.of("modality", "web", "query", "x", "num", 1)),
                "sourceAffinity", Map.of(
                        "modality:web", 0.5,
                        "instance:serper-main", 0.9)));
        SearchResult sr = oneResultSearchResult(SearchModality.WEB, "serper-main",
                List.of(hit("Alpha", "https://a/")));
        when(zarniwoopService.search(any(), any(), any())).thenReturn(sr);
        stubEvaluate(Map.of("verdicts", List.of(
                Map.of("hitId", "h0", "verdict", "keep", "relevanceScore", 0.8))));

        RankedHitSet result = service.investigate("topic", SCOPE, CTX);

        // Instance affinity wins over the modality affinity.
        assertThat(result.keptHits().get(0).sourceAffinityApplied()).isEqualTo(0.9);
        assertThat(result.keptHits().get(0).finalScore()).isEqualTo(0.8 * 0.9);
    }

    @Test
    void hits_are_url_deduped_across_steps_first_step_wins() {
        stubPlan(Map.of("steps", List.of(
                Map.of("modality", "web", "query", "a", "num", 5),
                Map.of("modality", "web", "query", "b", "num", 5))));
        SearchResult first = oneResultSearchResult(SearchModality.WEB, "serper-main",
                List.of(hit("First", "https://shared/"), hit("Solo", "https://solo/")));
        SearchResult second = oneResultSearchResult(SearchModality.WEB, "serper-eu",
                List.of(hit("Duplicate-but-different-title", "https://shared/"),
                        hit("Other", "https://other/")));
        when(zarniwoopService.search(any(SearchRequest.class), any(), any()))
                .thenReturn(first, second);
        stubEvaluate(Map.of("verdicts", List.of(
                Map.of("hitId", "h0", "verdict", "keep", "relevanceScore", 1.0),
                Map.of("hitId", "h1", "verdict", "keep", "relevanceScore", 0.5),
                Map.of("hitId", "h2", "verdict", "keep", "relevanceScore", 0.5))));

        RankedHitSet result = service.investigate("topic", SCOPE, CTX);

        assertThat(result.keptHits()).hasSize(3);
        assertThat(result.keptHits().stream().map(h -> h.url()))
                .containsExactlyInAnyOrder("https://shared/", "https://solo/", "https://other/");
        // The shared URL came from the first step → its title wins.
        boolean firstWins = result.keptHits().stream()
                .anyMatch(h -> h.url().equals("https://shared/") && h.title().equals("First"));
        assertThat(firstWins).isTrue();
        assertThat(result.instancesUsed()).containsExactlyInAnyOrder("serper-main", "serper-eu");
    }

    @Test
    void evaluate_failure_keeps_all_hits_with_neutral_score() {
        stubPlan(Map.of("steps", List.of(Map.of("modality", "web", "query", "x", "num", 1))));
        when(zarniwoopService.search(any(), any(), any()))
                .thenReturn(oneResultSearchResult(SearchModality.WEB, "serper-main",
                        List.of(hit("Alpha", "https://a/"))));
        when(lightLlm.callForJson(argRecipe(ZarniwoopResearchService.RECIPE_EVALUATE)))
                .thenThrow(new RuntimeException("recipe blew up"));

        RankedHitSet result = service.investigate("topic", SCOPE, CTX);

        assertThat(result.keptHits()).hasSize(1);
        assertThat(result.keptHits().get(0).relevanceScore()).isEqualTo(0.5);
        assertThat(result.gaps()).anyMatch(g -> g.contains("evaluate"));
    }

    @Test
    void plan_failure_falls_through_to_empty_set() {
        when(lightLlm.callForJson(argRecipe(ZarniwoopResearchService.RECIPE_PLAN)))
                .thenThrow(new RuntimeException("recipe blew up"));

        RankedHitSet result = service.investigate("topic", SCOPE, CTX);

        assertThat(result.keptHits()).isEmpty();
        verify(zarniwoopService, never()).search(any(), any(), any());
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private void stubPlan(Map<String, Object> planJson) {
        when(lightLlm.callForJson(argRecipe(ZarniwoopResearchService.RECIPE_PLAN)))
                .thenReturn(planJson);
    }

    private void stubEvaluate(Map<String, Object> verdictsJson) {
        when(lightLlm.callForJson(argRecipe(ZarniwoopResearchService.RECIPE_EVALUATE)))
                .thenReturn(verdictsJson);
    }

    private static LightLlmRequest argRecipe(String recipe) {
        return org.mockito.ArgumentMatchers.argThat(r ->
                r != null && recipe.equals(r.getRecipeName()));
    }

    private static SearchHit hit(String title, String url) {
        return new SearchHit(
                title, url, "snippet for " + title, "src",
                SearchModality.WEB, null, Map.of());
    }

    private static SearchResult emptyResult(SearchModality modality) {
        return new SearchResult(
                "q", modality, "serper-main", SearchTier.NORMAL,
                List.of(), 0, 0, null, null, Map.of());
    }

    private static SearchResult oneResultSearchResult(
            SearchModality modality, String instanceId, List<SearchHit> hits) {
        return new SearchResult(
                "q", modality, instanceId, SearchTier.NORMAL,
                hits, hits.size(), 0, null, null, Map.of());
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> toLinkedMap(Map<String, Object> m) {
        return new LinkedHashMap<>(m);
    }
}
