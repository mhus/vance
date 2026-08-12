package de.mhus.vance.brain.tools.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.toolusage.ToolUsageService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Limit resolution. The load-bearing rule is the minimum over the whole
 * fallback chain: the manifest is built once per turn, but the resilient
 * layer may advance afterwards — a budget that only knew the primary
 * model would hand the fallback a request it must reject (which is how
 * the 2026-08-12 incident burned both entries on the same 400).
 */
class ToolBudgetServiceTest {

    private AiModelResolver resolver;
    private ModelCatalog catalog;
    private ObservedToolLimitRegistry observed;
    private ToolUsageService usage;
    private ToolBudgetProperties properties;
    private ToolBudgetService service;

    @BeforeEach
    void setUp() {
        resolver = mock(AiModelResolver.class);
        catalog = mock(ModelCatalog.class);
        observed = new ObservedToolLimitRegistry();
        usage = mock(ToolUsageService.class);
        when(usage.demandByTool(any(), any())).thenReturn(Map.of());
        properties = new ToolBudgetProperties();
        service = new ToolBudgetService(resolver, catalog, observed, usage, properties);
    }

    @Test
    void catalogValue_becomesTheLimit() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);

        assertThat(service.limitFor(process("openai:gpt-x", List.of()), "proj")).hasValue(128);
    }

    @Test
    void noCatalogValueAnywhere_meansNoLimit() {
        stubModel("anthropic:claude-x", "anthropic", "claude-x", null);

        assertThat(service.limitFor(process("anthropic:claude-x", List.of()), "proj")).isEmpty();
    }

    @Test
    void chainLimit_isTheMinimumOverAllEntries() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        stubModel("cheap", "openai", "gpt-small", 64);

        assertThat(service.limitFor(process("openai:gpt-x", List.of("cheap")), "proj")).hasValue(64);
    }

    @Test
    void fallbackWithoutALimit_doesNotWidenTheBudget() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        stubModel("big", "anthropic", "claude-x", null);

        assertThat(service.limitFor(process("openai:gpt-x", List.of("big")), "proj")).hasValue(128);
    }

    @Test
    void learnedLimit_tightensTheCatalogValue() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        observed.learnFrom("openai:gpt-x", "maximum length 96", 128);

        assertThat(service.limitFor(process("openai:gpt-x", List.of()), "proj")).hasValue(96);
    }

    @Test
    void learnedLimit_appliesEvenWithoutCatalogMetadata() {
        stubModel("openai:gpt-x", "openai", "gpt-x", null);
        observed.learnFrom("openai:gpt-x", "maximum length 128", 200);

        assertThat(service.limitFor(process("openai:gpt-x", List.of()), "proj")).hasValue(128);
    }

    @Test
    void learningANewLimit_invalidatesTheMemo() {
        stubModel("openai:gpt-x", "openai", "gpt-x", null);
        ThinkProcessDocument process = process("openai:gpt-x", List.of());
        assertThat(service.limitFor(process, "proj")).isEmpty();

        observed.learnFrom("openai:gpt-x", "maximum length 128", 200);

        assertThat(service.limitFor(process, "proj")).hasValue(128);
    }

    @Test
    void budgetReservesHeadroomForRuntimeActivations() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        properties.setActivationHeadroom(8);
        properties.setExternalReserve(1);

        ToolBudget budget = service.forProcess(process("openai:gpt-x", List.of()), "proj", Map.of());

        assertThat(budget.maxTools()).isEqualTo(128);
        assertThat(budget.reserved()).isEqualTo(9);
        assertThat(budget.effectiveLimit()).isEqualTo(119);
    }

    @Test
    void disabledByProperty_yieldsNoBudgetAtAll() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        properties.setEnabled(false);

        ToolBudget budget = service.forProcess(process("openai:gpt-x", List.of()), "proj", Map.of());

        assertThat(budget.hasLimit()).isFalse();
    }

    @Test
    void unresolvableModelSpec_doesNotBreakTheTurn() {
        when(resolver.resolveOrDefault(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("no api key"));

        assertThat(service.limitFor(process("broken", List.of()), "proj")).isEmpty();
    }

    @Test
    void familyHints_comeFromProperties() {
        properties.setKeepFamilies(List.of("doc", "file"));
        properties.setDropFirstFamilies(List.of("slack_rest"));

        ToolTriage.Hints hints = service.familyHints();

        assertThat(hints.keepFamilies()).containsExactlyInAnyOrder("doc", "file");
        assertThat(hints.dropFirstFamilies()).containsExactly("slack_rest");
    }

    /**
     * Stubs one chain entry. {@code spec} is the string the service
     * actually resolves — a bare {@code params.model} is normalised to
     * {@code default:<name>} by {@code AiModelResolver.parseModelSpec},
     * so the tests use the qualified form for the primary entry and plain
     * aliases for fallbacks (which pass through verbatim).
     */
    private void stubModel(String spec, String provider, String model, Integer maxTools) {
        AiModelResolver.Resolved resolved =
                new AiModelResolver.Resolved(provider, provider, model);
        when(resolver.resolveOrDefault(eq(spec), any(), any(), any())).thenReturn(resolved);
        when(catalog.lookupOrDefault(any(), any(), eq(provider), eq(provider), eq(model)))
                .thenReturn(modelInfo(provider, model, maxTools));
    }

    private static ModelInfo modelInfo(String provider, String model, Integer maxTools) {
        return new ModelInfo(provider, model, 128_000, 8192, ModelSize.LARGE, Set.of(),
                60, 2, false, null, null,
                de.mhus.vance.brain.ai.OutputTokenParam.MAX_TOKENS, Set.of(), null, maxTools);
    }

    private static ThinkProcessDocument process(String modelSpec, List<String> fallbacks) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("p-1");
        p.setTenantId("acme");
        p.setProjectId("proj");
        Map<String, Object> params = new HashMap<>();
        params.put("model", modelSpec);
        if (!fallbacks.isEmpty()) {
            params.put("fallbackModels", fallbacks);
        }
        p.setEngineParams(params);
        return p;
    }
}
