package de.mhus.vance.brain.kit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KitNameResolverServiceTest {

    private LightLlmService lightLlm;
    private ProjectKitsCatalogService catalogService;
    private KitNameResolverService resolver;

    @BeforeEach
    void setUp() {
        lightLlm = mock(LightLlmService.class);
        catalogService = mock(ProjectKitsCatalogService.class);
        resolver = new KitNameResolverService(lightLlm, catalogService);
    }

    @Test
    void resolve_match_returnsKitNameAndRationale() {
        givenCatalog("school-essay", "creative-essay");
        when(catalogService.findByName("acme", "school-essay"))
                .thenReturn(entry("school-essay"));
        whenLightLlmReturns(Map.of(
                "decision", "MATCH",
                "kitName", "school-essay",
                "rationale", "Wish 'Schul-Aufsatz' matches the school-essay kit verbatim."));

        KitNameResolverService.Result r = resolver.resolve("acme", "lit-review", "Schul-Aufsatz");

        assertThat(r.matched()).isTrue();
        assertThat(r.kitName()).isEqualTo("school-essay");
        assertThat(r.rationale()).contains("school-essay");
    }

    @Test
    void resolve_match_butCatalogMissesName_returnsNoneWithSyntheticRationale() {
        givenCatalog("school-essay");
        when(catalogService.findByName("acme", "ghost-essay")).thenReturn(null);
        whenLightLlmReturns(Map.of(
                "decision", "MATCH",
                "kitName", "ghost-essay",
                "rationale", "Plausible match but hallucinated name."));

        KitNameResolverService.Result r = resolver.resolve("acme", "lit-review", "Aufsatz");

        assertThat(r.matched()).isFalse();
        assertThat(r.kitName()).isNull();
        assertThat(r.rationale()).contains("ghost-essay").contains("not in the catalog");
    }

    @Test
    void resolve_none_surfacesLlmRationale() {
        givenCatalog("research");
        whenLightLlmReturns(Map.of(
                "decision", "NONE",
                "kitName", "",
                "rationale", "No catalog entry fits the wish."));

        KitNameResolverService.Result r = resolver.resolve("acme", "lit-review", "weather forecast");

        assertThat(r.matched()).isFalse();
        assertThat(r.rationale()).isEqualTo("No catalog entry fits the wish.");
    }

    @Test
    void resolve_schemaBudgetExhausted_returnsNoneWithAttemptCount() {
        givenCatalog("research");
        when(lightLlm.callForJson(any()))
                .thenThrow(new SchemaValidationException(2, Map.of(), "missing required property 'decision'"));

        KitNameResolverService.Result r = resolver.resolve("acme", "lit-review", "anything");

        assertThat(r.matched()).isFalse();
        assertThat(r.rationale()).contains("2 attempts");
    }

    @Test
    void resolve_lightLlmFailure_returnsNoneWithErrorMessage() {
        givenCatalog("research");
        when(lightLlm.callForJson(any()))
                .thenThrow(new LightLlmException("LLM call failed: connection reset"));

        KitNameResolverService.Result r = resolver.resolve("acme", "lit-review", "anything");

        assertThat(r.matched()).isFalse();
        assertThat(r.rationale()).contains("connection reset");
    }

    @Test
    void resolve_emptyCatalog_skipsLightLlmCall() {
        when(catalogService.load("acme"))
                .thenReturn(new ProjectKitsCatalogDto(1, List.of()));

        KitNameResolverService.Result r = resolver.resolve("acme", "lit-review", "anything");

        assertThat(r.matched()).isFalse();
        assertThat(r.rationale()).contains("tenant catalog is empty");
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void resolve_blankWish_returnsNoneWithoutLlmCall() {
        KitNameResolverService.Result r = resolver.resolve("acme", "lit-review", "  ");

        assertThat(r.matched()).isFalse();
        assertThat(r.rationale()).contains("empty wish");
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void resolve_passesEntriesAsPebbleVar() {
        givenCatalog("school-essay", "research");
        when(catalogService.findByName(eq("acme"), eq("school-essay")))
                .thenReturn(entry("school-essay"));
        whenLightLlmReturns(Map.of(
                "decision", "MATCH",
                "kitName", "school-essay",
                "rationale", "match"));

        resolver.resolve("acme", "lit-review", "Aufsatz");

        ArgumentCaptor<LightLlmRequest> cap = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(cap.capture());
        LightLlmRequest req = cap.getValue();
        assertThat(req.getRecipeName()).isEqualTo(KitNameResolverService.RECIPE_NAME);
        assertThat(req.getTenantId()).isEqualTo("acme");
        assertThat(req.getProjectId()).isEqualTo("lit-review");
        assertThat(req.getUserPrompt()).isEqualTo("Aufsatz");
        assertThat(req.getPebbleVars()).containsKey("wish").containsKey("entries");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> entries =
                (List<Map<String, String>>) req.getPebbleVars().get("entries");
        assertThat(entries).extracting(m -> m.get("name"))
                .containsExactly("school-essay", "research");
    }

    // ──────────────────── helpers ────────────────────

    private void givenCatalog(String... names) {
        List<ProjectKitEntry> list = new java.util.ArrayList<>();
        for (String n : names) list.add(entry(n));
        when(catalogService.load("acme"))
                .thenReturn(new ProjectKitsCatalogDto(1, list));
    }

    private void whenLightLlmReturns(Map<String, Object> reply) {
        when(lightLlm.callForJson(any())).thenReturn(new LinkedHashMap<>(reply));
    }

    private static ProjectKitEntry entry(String name) {
        ProjectKitEntry e = new ProjectKitEntry();
        e.setName(name);
        e.setTitle(name + " title");
        e.setDescription("desc of " + name);
        return e;
    }
}
