package de.mhus.vance.brain.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DiscoveryService}. Mocks
 * {@link SourceCatalogService} and {@link LightLlmService}; the
 * tests verify the normalisation + anti-hallucination layer that
 * sits on top.
 */
class DiscoveryServiceTest {

    private static final String TENANT = "acme";
    private static final String CATALOG_WITH_TWO_MANUALS = """
            ## Manuals

            ### embed-images

            How to embed images.

            ### embed-overview

            Routing index for embedding visual content.
            """;

    private SourceCatalogService catalogService;
    private LightLlmService lightLlm;
    private DiscoveryService service;

    @BeforeEach
    void setUp() {
        catalogService = mock(SourceCatalogService.class);
        lightLlm = mock(LightLlmService.class);
        when(catalogService.renderForTenant(any(), any()))
                .thenReturn(CATALOG_WITH_TWO_MANUALS);
        service = new DiscoveryService(catalogService, lightLlm);
    }

    @Test
    void discover_returns_loaded_match_when_LLM_emits_loaded_with_known_name() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "loaded", Map.of(
                        "type", "manual",
                        "name", "embed-images",
                        "source", "engine",
                        "summary", "How to embed images.")));

        DiscoveryResult r = service.discover("show me a picture", TENANT, null, null);
        assertThat(r.getLoaded()).isNotNull();
        assertThat(r.getLoaded().getName()).isEqualTo("embed-images");
        assertThat(r.getLoaded().getSummary()).contains("embed images");
        assertThat(r.getAlternatives()).isEmpty();
        assertThat(r.getHint()).isNull();
    }

    @Test
    void discover_returns_alternatives_when_LLM_emits_list() {
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
                .extracting(DiscoveryResult.Match::getName)
                .containsExactly("embed-overview", "embed-images");
        assertThat(r.getHint()).isNull();
    }

    @Test
    void discover_returns_hint_when_LLM_emits_hint() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "hint", "no match — be more specific"));

        DiscoveryResult r = service.discover("xyzzy gibberish", TENANT, null, null);
        assertThat(r.getLoaded()).isNull();
        assertThat(r.getAlternatives()).isEmpty();
        assertThat(r.getHint()).isEqualTo("no match — be more specific");
    }

    @Test
    void discover_downgrades_to_hint_when_loaded_name_not_in_catalog() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "loaded", Map.of(
                        "type", "manual",
                        "name", "embed-hallucinated",
                        "source", "engine",
                        "summary", "made up summary")));

        DiscoveryResult r = service.discover("hmm", TENANT, null, null);
        assertThat(r.getLoaded()).isNull();
        assertThat(r.getHint()).contains("embed-hallucinated");
        assertThat(r.getHint()).contains("isn't in the catalog");
    }

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
