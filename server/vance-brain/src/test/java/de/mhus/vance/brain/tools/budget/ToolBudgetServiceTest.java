package de.mhus.vance.brain.tools.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        when(usage.demandByTool(any(), any(), any())).thenReturn(Map.of());
        properties = new ToolBudgetProperties();
        service = new ToolBudgetService(resolver, catalog, observed, usage, properties);
    }

    @Test
    void catalogValue_becomesTheLimit() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);

        assertThat(service.limitFor(process("openai:gpt-x", List.of()))).hasValue(128);
    }

    @Test
    void noCatalogValueAnywhere_meansNoLimit() {
        stubModel("anthropic:claude-x", "anthropic", "claude-x", null);

        assertThat(service.limitFor(process("anthropic:claude-x", List.of()))).isEmpty();
    }

    @Test
    void chainLimit_isTheMinimumOverAllEntries() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        stubModel("cheap", "openai", "gpt-small", 64);

        assertThat(service.limitFor(process("openai:gpt-x", List.of("cheap")))).hasValue(64);
    }

    @Test
    void fallbackWithoutALimit_doesNotWidenTheBudget() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        stubModel("big", "anthropic", "claude-x", null);

        assertThat(service.limitFor(process("openai:gpt-x", List.of("big")))).hasValue(128);
    }

    @Test
    void learnedLimit_tightensTheCatalogValue() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        observed.learnFrom("openai:gpt-x", "maximum length 96", 128);

        assertThat(service.limitFor(process("openai:gpt-x", List.of()))).hasValue(96);
    }

    @Test
    void learnedLimit_appliesEvenWithoutCatalogMetadata() {
        stubModel("openai:gpt-x", "openai", "gpt-x", null);
        observed.learnFrom("openai:gpt-x", "maximum length 128", 200);

        assertThat(service.limitFor(process("openai:gpt-x", List.of()))).hasValue(128);
    }

    @Test
    void learningANewLimit_invalidatesTheMemo() {
        stubModel("openai:gpt-x", "openai", "gpt-x", null);
        ThinkProcessDocument process = process("openai:gpt-x", List.of());
        assertThat(service.limitFor(process)).isEmpty();

        observed.learnFrom("openai:gpt-x", "maximum length 128", 200);

        assertThat(service.limitFor(process)).hasValue(128);
    }

    @Test
    void byDefault_onlyTheEngineActionToolIsReserved() {
        // Headroom is 0 on purpose: every path that grows the manifest
        // after the classification re-runs the triage, so a standing
        // cushion would park capability in the discovery block for
        // nothing. The one reserved slot is the action tool the engine
        // appends outside primaryAsLc4j().
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);

        ToolBudget budget = service.forProcess(process("openai:gpt-x", List.of()), "proj", Map.of());

        assertThat(budget.reserved()).isEqualTo(1);
        assertThat(budget.effectiveLimit()).isEqualTo(127);
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

        assertThat(service.limitFor(process("broken", List.of()))).isEmpty();
    }

    @Test
    void demandIsReadPerRole_recipeFirst() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        ThinkProcessDocument p = process("openai:gpt-x", List.of());
        p.setRecipeName("arthur");
        p.setThinkEngine("arthur-engine");
        when(usage.demandByTool("acme", "proj", "arthur"))
                .thenReturn(Map.of("doc_read", 7L));

        ToolBudget budget = service.forProcess(p, "proj", Map.of());

        assertThat(budget.usage()).containsEntry("doc_read", 7L);
    }

    @Test
    void demandRole_fallsBackToTheEngine_whenNoRecipeIsSet() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        ThinkProcessDocument p = process("openai:gpt-x", List.of());
        p.setThinkEngine("frankie");
        when(usage.demandByTool("acme", "proj", "frankie"))
                .thenReturn(Map.of("file_read", 153L));

        ToolBudget budget = service.forProcess(p, "proj", Map.of());

        assertThat(budget.usage()).containsEntry("file_read", 153L);
    }

    @Test
    void tenantPinnedProcess_resolvesTheChainFromTheTenantLayerOnly() {
        // params.aiScope: tenant makes the chat resolve alias, endpoint and
        // catalog from _tenant. The budget has to look at the same layer or
        // it may cap a model the request is never sent to.
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        ThinkProcessDocument p = process("openai:gpt-x", List.of());
        p.getEngineParams().put("aiScope", "tenant");

        assertThat(service.limitFor(p)).hasValue(128);

        verify(resolver).resolveOrDefault(eq("openai:gpt-x"), eq("acme"), isNull(), isNull());
        verify(catalog).lookupOrDefault(
                eq("acme"), isNull(), eq("openai"), eq("openai"), eq("gpt-x"));
    }

    @Test
    void unpinnedProcess_stillResolvesThroughTheProjectCascade() {
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);

        assertThat(service.limitFor(process("openai:gpt-x", List.of()))).hasValue(128);

        verify(resolver).resolveOrDefault(eq("openai:gpt-x"), eq("acme"), eq("proj"), eq("p-1"));
    }

    @Test
    void pinnedAndUnpinnedProcesses_doNotShareAMemoEntry() {
        // Same tenant, same spec — but different scopes, so the cached
        // limit of one must not be served to the other.
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        ThinkProcessDocument pinned = process("openai:gpt-x", List.of());
        pinned.getEngineParams().put("aiScope", "tenant");

        service.limitFor(process("openai:gpt-x", List.of()));
        service.limitFor(pinned);

        verify(resolver).resolveOrDefault(eq("openai:gpt-x"), eq("acme"), eq("proj"), eq("p-1"));
        verify(resolver).resolveOrDefault(eq("openai:gpt-x"), eq("acme"), isNull(), isNull());
    }

    @Test
    void twoProcessesInOneProject_doNotShareAMemoEntry() {
        // The process id is a settings layer of its own, and the alias is
        // resolved through it. Sharing the memo would let whichever process
        // ran first decide the other's budget for the cache TTL.
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        ThinkProcessDocument a = process("openai:gpt-x", List.of());
        ThinkProcessDocument b = process("openai:gpt-x", List.of());
        b.setId("p-2");

        service.limitFor(a);
        service.limitFor(b);

        verify(resolver).resolveOrDefault(eq("openai:gpt-x"), eq("acme"), eq("proj"), eq("p-1"));
        verify(resolver).resolveOrDefault(eq("openai:gpt-x"), eq("acme"), eq("proj"), eq("p-2"));
    }

    @Test
    void aiScope_followsTheProcessProject_notTheWorkingProject() {
        // ChatBehaviorBuilder.fromProcess and EngineChatFactory both read
        // process.getProjectId(); reading the cap through a cross-project
        // worker's *working* project would budget a different endpoint.
        stubModel("openai:gpt-x", "openai", "gpt-x", 128);
        ThinkProcessDocument p = process("openai:gpt-x", List.of());
        p.setProjectId("home-project");

        service.forProcess(p, "working-project", Map.of());

        verify(resolver).resolveOrDefault(
                eq("openai:gpt-x"), eq("acme"), eq("home-project"), eq("p-1"));
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
