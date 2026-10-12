package de.mhus.vance.brain.ai.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelQuirks;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the bundled decision model documents ({@code kind: decision})
 * and keeps the kind disjunct: a decision model must never show up as a
 * chat model, nor a chat model as a decision model.
 */
class DecisionModelCatalogTest {

    private ModelCatalog catalog;

    @BeforeEach
    void setUp() {
        DocumentService documentService = mock(DocumentService.class);
        when(documentService.findAllByPathPrefix(ModelCatalog.MODEL_PATH_PREFIX))
                .thenReturn(List.<DocumentDocument>of());
        catalog = new ModelCatalog(documentService, new ModelQuirks());
    }

    @Test
    void bundled_jev_carries_the_measured_limits() {
        DecisionModelInfo jev = catalog.lookupDecision(null, null, "openrouter", "typesafe/jev-1.13")
                .orElseThrow();

        assertThat(jev.maxChoiceOptions()).isEqualTo(255);
        assertThat(jev.maxScoreLevels()).isEqualTo(10);
        assertThat(jev.maxQuestions()).isEqualTo(64);
        assertThat(jev.structuredCriteria()).isTrue();
        assertThat(jev.costPerMillionInput()).isEqualTo(0.042);
        assertThat(jev.rateLimitRps()).isEqualTo(80.0);
        assertThat(jev.maxConcurrentCalls()).isNull();
        assertThat(jev.timeoutSeconds()).isEqualTo(10);
    }

    @Test
    void bundled_nimble_is_the_minimal_profile_and_serial() {
        DecisionModelInfo nimble =
                catalog.lookupDecision(null, null, "ollama", "nimble").orElseThrow();

        assertThat(nimble.maxChoiceOptions()).isEqualTo(26);
        assertThat(nimble.structuredCriteria()).isFalse();
        assertThat(nimble.maxConcurrentCalls()).isEqualTo(1);
        assertThat(nimble.costForInputTokens(1000)).isZero();
    }

    @Test
    void kinds_stay_disjunct() {
        assertThat(catalog.lookup(null, null, "openrouter", "typesafe/jev-1.13"))
                .isEmpty();
        assertThat(catalog.lookupDecision(null, null, "ollama", "qwen3:8b")).isEmpty();
        assertThat(catalog.listAllDecision(null, null))
                .extracting(DecisionModelInfo::modelName)
                .contains("typesafe/jev-1.13", "nimble");
    }
}
