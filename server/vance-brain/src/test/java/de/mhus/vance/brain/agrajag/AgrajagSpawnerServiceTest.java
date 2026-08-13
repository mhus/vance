package de.mhus.vance.brain.agrajag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.brain.agrajag.engine.AgrajagEngine;
import de.mhus.vance.brain.ai.AiConfigScope;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class AgrajagSpawnerServiceTest {

    private SessionService sessionService;
    private ThinkProcessService thinkProcessService;
    private LaneScheduler laneScheduler;
    private ThinkEngineService engines;
    private RecipeResolver recipeResolver;
    private AgrajagSpawnerService spawner;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        laneScheduler = new LaneScheduler();
        engines = mock(ThinkEngineService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ThinkEngineService> engineProvider = mock(ObjectProvider.class);
        when(engineProvider.getObject()).thenReturn(engines);
        recipeResolver = mock(RecipeResolver.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RecipeResolver> recipeProvider = mock(ObjectProvider.class);
        when(recipeProvider.getObject()).thenReturn(recipeResolver);
        // Default stub: recipe contributes the model/probe config, caller
        // params (the incident) are merged on top — same order as the real
        // RecipeResolver.apply.
        when(recipeResolver.applyDefaulting(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> appliedRecipe(inv.getArgument(4)));

        spawner = new AgrajagSpawnerService(
                sessionService, thinkProcessService, laneScheduler,
                engineProvider, recipeProvider);
    }

    /** Stand-in for the bundled agrajag recipe: params + tenant-pinned AI config. */
    private static AppliedRecipe appliedRecipe(@Nullable Map<String, Object> callerParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", "default:agrajag,default:fast");
        params.put("maxProbes", 3);
        params.put(AiConfigScope.PARAM_KEY, "tenant");
        if (callerParams != null) {
            params.putAll(callerParams);
        }
        return new AppliedRecipe(
                AgrajagEngine.NAME, AgrajagEngine.NAME, params,
                /*promptOverride*/ null, /*promptOverrideAppend*/ null,
                PromptMode.APPEND, /*dataRelayCorrection*/ null,
                /*effectiveAllowedTools*/ null, /*connectionProfile*/ null,
                List.of(), /*allowedSkills*/ null,
                RecipeSource.RESOURCE, List.of(),
                /*sessionLifecycleConfig*/ null);
    }

    @AfterEach
    void tearDown() {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(laneScheduler, "shutdown");
    }

    @Test
    void spawnDiagnosis_createsSessionLazilyOnFirstCall() {
        SessionDocument fresh = newSession("sess-agrajag");
        when(sessionService.findSystemSession("acme", "proj-1",
                AgrajagSpawnerService.AGRAJAG_SESSION_NAME))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(fresh));
        when(sessionService.create(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), eq(true)))
                .thenReturn(fresh);
        when(thinkProcessService.create(anyString(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenAnswer(inv -> newProcess(inv.getArgument(3)));

        spawner.spawnDiagnosis(
                "acme", "proj-1", "mcp_search",
                ToolHealthScope.PROJECT, "proj-1",
                "http-5xx", "alice", "MCP gateway returning 502s");

        verify(sessionService).create(
                eq("acme"),
                eq(SessionService.SYSTEM_OWNER),
                eq("proj-1"),
                eq(AgrajagSpawnerService.AGRAJAG_SESSION_NAME),
                anyString(), anyString(), any(), eq(true));
        verify(sessionService).markBootstrapped(eq("sess-agrajag"));
    }

    @Test
    void spawnDiagnosis_reusesSessionWhenPresent() {
        SessionDocument existing = newSession("sess-agrajag");
        when(sessionService.findSystemSession("acme", "proj-1",
                AgrajagSpawnerService.AGRAJAG_SESSION_NAME))
                .thenReturn(Optional.of(existing));
        when(thinkProcessService.create(anyString(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenAnswer(inv -> newProcess(inv.getArgument(3)));

        spawner.spawnDiagnosis(
                "acme", "proj-1", "mcp_search",
                ToolHealthScope.PROJECT, "proj-1",
                "http-5xx", "alice", null);

        verify(sessionService, never()).create(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), eq(true));
    }

    @Test
    void spawnDiagnosis_passesEngineParamsToProcessCreate() {
        SessionDocument existing = newSession("sess-agrajag");
        when(sessionService.findSystemSession(any(), any(), any()))
                .thenReturn(Optional.of(existing));
        when(thinkProcessService.create(anyString(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenAnswer(inv -> newProcess(inv.getArgument(3)));

        spawner.spawnDiagnosis(
                "acme", "proj-1", "mcp_search",
                ToolHealthScope.PROJECT, "proj-1",
                "http-5xx", "alice", "trigger note");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCap = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).create(
                eq("acme"), eq("proj-1"), eq("sess-agrajag"),
                anyString(),                  // processName (UUID suffix)
                eq(AgrajagEngine.NAME),
                eq(AgrajagEngine.VERSION),
                anyString(), anyString(), any(),
                paramsCap.capture(),
                eq(AgrajagEngine.NAME),
                any(), any(), any());

        Map<String, Object> params = paramsCap.getValue();
        assertThat(params)
                .containsEntry("toolName", "mcp_search")
                .containsEntry("scope", "PROJECT")
                .containsEntry("scopeId", "proj-1")
                .containsEntry("errorSignature", "http-5xx")
                .containsEntry("originatingUserId", "alice")
                .containsEntry("note", "trigger note");
    }

    @Test
    void spawnDiagnosis_mergesRecipeConfigWithIncidentParams() {
        when(sessionService.findSystemSession(any(), any(), any()))
                .thenReturn(Optional.of(newSession("sess-agrajag")));
        when(thinkProcessService.create(anyString(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenAnswer(inv -> newProcess(inv.getArgument(3)));

        spawner.spawnDiagnosis(
                "acme", "proj-1", "mcp_search",
                ToolHealthScope.PROJECT, "proj-1",
                "http-5xx", "alice", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCap = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).create(any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                paramsCap.capture(),
                any(), any(), any(), any());

        // Without the recipe the process would carry the incident only and
        // fall back to ai.default.* — the model/probe budget must be there.
        assertThat(paramsCap.getValue())
                .containsEntry("model", "default:agrajag,default:fast")
                .containsEntry("maxProbes", 3)
                .containsEntry(AiConfigScope.PARAM_KEY, "tenant")
                .containsEntry("toolName", "mcp_search");
    }

    @Test
    void spawnDiagnosis_recipeResolutionFailure_skipsSpawn() {
        when(sessionService.findSystemSession(any(), any(), any()))
                .thenReturn(Optional.of(newSession("sess-agrajag")));
        when(recipeResolver.applyDefaulting(any(), any(), any(), any(), any()))
                .thenThrow(new RecipeResolver.UnknownRecipeException(AgrajagEngine.NAME));

        spawner.spawnDiagnosis(
                "acme", "proj-1", "mcp_search",
                ToolHealthScope.PROJECT, "proj-1",
                "http-5xx", "alice", null);

        // A broken recipe override means no diagnosis — never a spawn on
        // unconfigured params, which is what produced the wrong-model bug.
        verify(thinkProcessService, never()).create(any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void spawnDiagnosis_omitsNoteWhenNull() {
        when(sessionService.findSystemSession(any(), any(), any()))
                .thenReturn(Optional.of(newSession("sess-agrajag")));
        when(thinkProcessService.create(anyString(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenAnswer(inv -> newProcess(inv.getArgument(3)));

        spawner.spawnDiagnosis(
                "acme", "proj-1", "mcp_search",
                ToolHealthScope.PROJECT, "proj-1",
                "http-5xx", "alice", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCap = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).create(any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                paramsCap.capture(),
                any(), any(), any(), any());
        assertThat(paramsCap.getValue()).doesNotContainKey("note");
    }

    @Test
    void spawnDiagnosis_swallowsSessionCreateFailure() {
        when(sessionService.findSystemSession(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(sessionService.create(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), eq(true)))
                .thenThrow(new RuntimeException("Mongo dropped"));

        spawner.spawnDiagnosis(
                "acme", "proj-1", "mcp_search",
                ToolHealthScope.PROJECT, "proj-1",
                "http-5xx", "alice", null);

        verify(thinkProcessService, never()).create(any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void spawnDiagnosis_nullProjectIdYieldsEmptyString() {
        SessionDocument fresh = newSession("sess-agrajag");
        when(sessionService.findSystemSession("acme", "",
                AgrajagSpawnerService.AGRAJAG_SESSION_NAME))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(fresh));
        when(sessionService.create(anyString(), anyString(), eq(""),
                anyString(), anyString(), anyString(), any(), eq(true)))
                .thenReturn(fresh);
        when(thinkProcessService.create(anyString(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenAnswer(inv -> newProcess(inv.getArgument(3)));

        spawner.spawnDiagnosis(
                "acme", null, "mcp_search",
                ToolHealthScope.TENANT, "acme",
                "http-5xx", "alice", null);

        verify(sessionService, times(1)).create(
                anyString(), anyString(), eq(""),
                anyString(), anyString(), anyString(), any(), eq(true));
    }

    private static SessionDocument newSession(String sessionId) {
        SessionDocument s = new SessionDocument();
        s.setSessionId(sessionId);
        s.setTenantId("acme");
        s.setProjectId("proj-1");
        s.setSystem(true);
        return s;
    }

    private static ThinkProcessDocument newProcess(String name) {
        return ThinkProcessDocument.builder()
                .id("proc-" + name)
                .tenantId("acme")
                .projectId("proj-1")
                .sessionId("sess-agrajag")
                .name(name)
                .thinkEngine(AgrajagEngine.NAME)
                .build();
    }
}
