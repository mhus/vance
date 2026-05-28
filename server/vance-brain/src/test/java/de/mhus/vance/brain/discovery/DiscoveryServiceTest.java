package de.mhus.vance.brain.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DiscoveryService}. Mocks
 * {@link SourceCatalogService}, {@link LightLlmService} and
 * {@link DocumentService}; the tests verify the normalisation +
 * anti-hallucination retry layer + server-side body auto-load.
 */
class DiscoveryServiceTest {

    private static final String TENANT = "acme";
    private static final String CATALOG_WITH_TWO_MANUALS = """
            ## Manuals

            ### embed-images

            **Title:** Embedding — Images
            **Summary:** How to embed images.

            ### embed-overview

            **Title:** Embedding — Overview
            **Summary:** Routing index for embedding visual content.
            """;

    private SourceCatalogService catalogService;
    private LightLlmService lightLlm;
    private DocumentService documentService;
    private DiscoveryService service;

    @BeforeEach
    void setUp() {
        catalogService = mock(SourceCatalogService.class);
        lightLlm = mock(LightLlmService.class);
        documentService = mock(DocumentService.class);
        when(catalogService.renderForTenant(any(), any()))
                .thenReturn(CATALOG_WITH_TWO_MANUALS);
        service = new DiscoveryService(catalogService, lightLlm, documentService);
    }

    // ── Loaded + auto-load happy path ──────────────────────────────

    @Test
    void discover_inlines_manual_body_on_confident_match() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "loaded", Map.of(
                        "type", "manual",
                        "name", "embed-images",
                        "source", "engine",
                        "summary", "How to embed images.")));
        // Document cascade returns the manual body.
        when(documentService.lookupCascade(eq(TENANT), any(), eq("manuals/embed-images.md")))
                .thenReturn(Optional.of(new LookupResult(
                        "manuals/embed-images.md",
                        "# Embedding — Images\n\nFull body text here.\n",
                        LookupResult.Source.RESOURCE, null)));

        DiscoveryResult r = service.discover("show me a picture", TENANT, null, null);

        assertThat(r.getLoaded()).isNotNull();
        assertThat(r.getLoaded().getName()).isEqualTo("embed-images");
        assertThat(r.getLoaded().getSummary()).contains("embed images");
        assertThat(r.getLoaded().getContent()).contains("Full body text here.");
        assertThat(r.getAlternatives()).isEmpty();
        assertThat(r.getHint()).isNull();
    }

    @Test
    void discover_does_not_load_body_for_skill_or_tool_loaded() {
        // Catalog won't contain a tool/skill section here, so we
        // simulate a stub catalog and assert the body lookup is NOT
        // hit when loaded.type isn't "manual".
        when(catalogService.renderForTenant(any(), any())).thenReturn("""
                ## Tools

                ### web_search

                Search the web.
                """);
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "loaded", Map.of(
                        "type", "tool",
                        "name", "web_search",
                        "source", "engine",
                        "summary", "Search the web.")));

        DiscoveryResult r = service.discover("search the web", TENANT, null, null);

        assertThat(r.getLoaded()).isNotNull();
        assertThat(r.getLoaded().getType()).isEqualTo("tool");
        assertThat(r.getLoaded().getContent()).isNull();
        verify(documentService, times(0)).lookupCascade(any(), any(), any());
    }

    // ── Alternatives + hint pass-throughs (no auto-load) ──────────

    @Test
    void discover_returns_alternatives_without_content() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "alternatives", List.of(
                        Map.of("type", "manual", "name", "embed-overview",
                                "source", "engine", "summary", "Routing", "score", 0.7),
                        Map.of("type", "manual", "name", "embed-images",
                                "source", "engine", "summary", "Images", "score", 0.6))));

        DiscoveryResult r = service.discover("something visual", TENANT, null, null);

        assertThat(r.getLoaded()).isNull();
        assertThat(r.getAlternatives()).hasSize(2);
        assertThat(r.getAlternatives())
                .extracting(DiscoveryResult.Match::getContent)
                .containsOnlyNulls();
        verify(documentService, times(0)).lookupCascade(any(), any(), any());
    }

    @Test
    void discover_returns_hint_when_LLM_emits_hint() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "hint", "no match — be more specific"));

        DiscoveryResult r = service.discover("xyzzy gibberish", TENANT, null, null);

        assertThat(r.getLoaded()).isNull();
        assertThat(r.getAlternatives()).isEmpty();
        assertThat(r.getHint()).isEqualTo("no match — be more specific");
        verify(documentService, times(0)).lookupCascade(any(), any(), any());
    }

    // ── Retry on hallucinated name ────────────────────────────────

    @Test
    void discover_retries_when_first_pick_is_not_in_catalog() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of(
                        "loaded", Map.of(
                                "type", "manual",
                                "name", "embed-hallucinated",
                                "source", "engine",
                                "summary", "made up")))
                .thenReturn(Map.of(
                        "loaded", Map.of(
                                "type", "manual",
                                "name", "embed-images",
                                "source", "engine",
                                "summary", "Images.")));
        when(documentService.lookupCascade(eq(TENANT), any(), eq("manuals/embed-images.md")))
                .thenReturn(Optional.of(new LookupResult(
                        "manuals/embed-images.md", "real body",
                        LookupResult.Source.RESOURCE, null)));

        DiscoveryResult r = service.discover("show pic", TENANT, null, null);

        assertThat(r.getLoaded()).isNotNull();
        assertThat(r.getLoaded().getName()).isEqualTo("embed-images");
        assertThat(r.getLoaded().getContent()).isEqualTo("real body");
        verify(lightLlm, times(2)).callForJson(any(LightLlmRequest.class));
    }

    @Test
    void discover_retries_when_known_name_fails_to_load_from_cascade() {
        // LLM picks a name that IS in the catalog header but the
        // document cascade can't load — treated as a soft hallucination
        // and retried.
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of(
                        "loaded", Map.of(
                                "type", "manual",
                                "name", "embed-images",
                                "source", "engine",
                                "summary", "Images.")))
                .thenReturn(Map.of(
                        "loaded", Map.of(
                                "type", "manual",
                                "name", "embed-overview",
                                "source", "engine",
                                "summary", "Routing.")));
        when(documentService.lookupCascade(eq(TENANT), any(), eq("manuals/embed-images.md")))
                .thenReturn(Optional.empty());
        when(documentService.lookupCascade(eq(TENANT), any(), eq("manuals/embed-overview.md")))
                .thenReturn(Optional.of(new LookupResult(
                        "manuals/embed-overview.md", "overview body",
                        LookupResult.Source.RESOURCE, null)));

        DiscoveryResult r = service.discover("show pic", TENANT, null, null);

        assertThat(r.getLoaded().getName()).isEqualTo("embed-overview");
        assertThat(r.getLoaded().getContent()).isEqualTo("overview body");
        verify(lightLlm, times(2)).callForJson(any(LightLlmRequest.class));
    }

    @Test
    void discover_downgrades_to_hint_when_all_attempts_hallucinate() {
        // Every attempt picks a name that isn't in the catalog. After
        // MAX_DISCOVERY_ATTEMPTS retries the service gives up with a
        // hint that names the offending picks.
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "loaded", Map.of(
                        "type", "manual",
                        "name", "embed-hallucinated",
                        "source", "engine",
                        "summary", "made up")));

        DiscoveryResult r = service.discover("hmm", TENANT, null, null);

        assertThat(r.getLoaded()).isNull();
        assertThat(r.getHint()).contains("embed-hallucinated");
        assertThat(r.getHint()).contains("3 attempts");
        verify(lightLlm, times(3)).callForJson(any(LightLlmRequest.class));
    }

    // ── Alternative filtering (existing behaviour preserved) ──────

    @Test
    void discover_filters_unknown_names_out_of_alternatives() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "alternatives", List.of(
                        Map.of("type", "manual", "name", "embed-hallucinated",
                                "source", "engine", "summary", "fake", "score", 0.5),
                        Map.of("type", "manual", "name", "embed-images",
                                "source", "engine", "summary", "Images", "score", 0.8))));

        DiscoveryResult r = service.discover("show pic", TENANT, null, null);

        assertThat(r.getAlternatives()).hasSize(1);
        assertThat(r.getAlternatives().get(0).getName()).isEqualTo("embed-images");
    }

    // ── Edge cases ────────────────────────────────────────────────

    @Test
    void discover_returns_fallback_hint_when_LLM_returns_empty_object() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of());

        DiscoveryResult r = service.discover("hmm", TENANT, null, null);
        assertThat(r.getHint()).contains("no usable result");
    }

    @Test
    void discover_throws_when_intent_blank() {
        assertThatThrownBy(() -> service.discover("   ", TENANT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intent");
    }

    @Test
    void discover_throws_when_tenantId_blank() {
        assertThatThrownBy(() -> service.discover("test", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void discover_propagates_LightLlm_failure() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenThrow(new LightLlmException("recipe not found: how-do-i"));

        assertThatThrownBy(() -> service.discover("test", TENANT, null, null))
                .isInstanceOf(LightLlmException.class)
                .hasMessageContaining("recipe not found");
    }

    @Test
    void knownCapability_recognises_h3_header() {
        assertThat(DiscoveryService.knownCapability("embed-images", CATALOG_WITH_TWO_MANUALS))
                .isTrue();
        assertThat(DiscoveryService.knownCapability("nonexistent", CATALOG_WITH_TWO_MANUALS))
                .isFalse();
        assertThat(DiscoveryService.knownCapability(null, CATALOG_WITH_TWO_MANUALS))
                .isFalse();
        assertThat(DiscoveryService.knownCapability("", CATALOG_WITH_TWO_MANUALS))
                .isFalse();
    }
}
