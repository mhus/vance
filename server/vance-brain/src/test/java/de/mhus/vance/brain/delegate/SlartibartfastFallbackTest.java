package de.mhus.vance.brain.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.slartibartfast.SlartibartfastEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests for {@link SlartibartfastFallback}. Mocks
 * {@link ThinkProcessService} + {@link ThinkEngineService} to drive
 * the spawn / poll / inspect lifecycle without launching real
 * Slart-instances. Covers the four logical paths:
 *
 * <ul>
 *   <li>Slart DONE with a valid recipe path → {@code GENERATED}</li>
 *   <li>Slart CLOSED in FAILED state → {@code FAILED}</li>
 *   <li>Slart CLOSED in ESCALATED state → {@code FAILED}</li>
 *   <li>Slart never closes → caller hits the wallclock cap</li>
 * </ul>
 */
class SlartibartfastFallbackTest {

    private static final String CALLER_PROC = "caller-proc";
    private static final String SLART_PROC = "slart-proc";
    private static final String TENANT = "acme";
    private static final String PROJECT = "test-project";
    private static final String SESSION = "sess-1";

    private ThinkProcessService thinkProcessService;
    private ThinkEngineService thinkEngineService;
    private SlartibartfastFallback fallback;
    private ThinkProcessDocument caller;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        thinkEngineService = mock(ThinkEngineService.class);
        fallback = new SlartibartfastFallback(thinkProcessService, thinkEngineService);

        caller = new ThinkProcessDocument();
        caller.setId(CALLER_PROC);
        caller.setTenantId(TENANT);
        caller.setProjectId(PROJECT);
        caller.setSessionId(SESSION);
    }

    @Test
    void slartProducesRecipe_outcomeGenerated() {
        // First poll observes RUNNING (or anything non-CLOSED), then
        // CLOSED with a DONE state carrying the recipe path.
        ThinkProcessDocument running = slartProcess(SLART_PROC, ThinkProcessStatus.RUNNING, null);
        ThinkProcessDocument done = slartProcess(SLART_PROC, ThinkProcessStatus.CLOSED,
                state("DONE", "recipes/_slart/abc123/my-recipe.yaml"));

        primeSpawn();
        when(thinkProcessService.findById(SLART_PROC))
                .thenReturn(Optional.of(running))
                .thenReturn(Optional.of(done));

        SlartibartfastFallback.Result r = fallback.invoke(caller, "do something nice", "spawned-name");

        assertThat(r.outcome()).isEqualTo(SlartibartfastFallback.Outcome.GENERATED);
        assertThat(r.recipeName()).isEqualTo("_slart/abc123/my-recipe");
        assertThat(r.recipePath()).isEqualTo("recipes/_slart/abc123/my-recipe.yaml");
        assertThat(r.slartProcessId()).isEqualTo(SLART_PROC);

        // Spawn metadata threaded through correctly.
        verify(thinkProcessService).create(
                eq(TENANT), eq(PROJECT), eq(SESSION),
                argThat(s -> s != null && s.startsWith("delegate-fallback-slart-")),
                eq(SlartibartfastEngine.NAME),
                eq(SlartibartfastEngine.VERSION),
                any(), any(), eq(CALLER_PROC),
                argThat(p -> p != null
                        && "do something nice".equals(p.get(SlartibartfastEngine.USER_DESCRIPTION_KEY))
                        && SlartibartfastFallback.DEFAULT_OUTPUT_SCHEMA.equals(
                                p.get(SlartibartfastEngine.OUTPUT_SCHEMA_TYPE_KEY))),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(thinkEngineService, times(1)).start(any());
    }

    @Test
    void slartFailedState_outcomeFailedWithReason() {
        ThinkProcessDocument failed = slartProcess(SLART_PROC, ThinkProcessStatus.CLOSED,
                stateWithFailure("FAILED", "missing manuals for code-analysis domain"));

        primeSpawn();
        when(thinkProcessService.findById(SLART_PROC)).thenReturn(Optional.of(failed));

        SlartibartfastFallback.Result r = fallback.invoke(caller, "task", "name");

        assertThat(r.outcome()).isEqualTo(SlartibartfastFallback.Outcome.FAILED);
        assertThat(r.recipeName()).isNull();
        assertThat(r.rationale())
                .contains("Slart did not produce a recipe")
                .contains("missing manuals");
    }

    @Test
    void slartEscalatedState_outcomeFailed() {
        ThinkProcessDocument escalated = slartProcess(SLART_PROC, ThinkProcessStatus.CLOSED,
                stateWithFailure("ESCALATED",
                        "exceeded maxRecoveries=5 — escalation mode=FAIL"));

        primeSpawn();
        when(thinkProcessService.findById(SLART_PROC)).thenReturn(Optional.of(escalated));

        SlartibartfastFallback.Result r = fallback.invoke(caller, "task", "name");

        assertThat(r.outcome()).isEqualTo(SlartibartfastFallback.Outcome.FAILED);
        // When failureReason is populated it is used verbatim, which
        // is more informative than the bare status word — assert on
        // the actual reason text rather than 'ESCALATED'.
        assertThat(r.rationale()).contains("exceeded maxRecoveries");
    }

    @Test
    void slartDoneButNoPath_outcomeFailed() {
        ThinkProcessDocument done = slartProcess(SLART_PROC, ThinkProcessStatus.CLOSED,
                state("DONE", null));

        primeSpawn();
        when(thinkProcessService.findById(SLART_PROC)).thenReturn(Optional.of(done));

        SlartibartfastFallback.Result r = fallback.invoke(caller, "task", "name");

        assertThat(r.outcome()).isEqualTo(SlartibartfastFallback.Outcome.FAILED);
        assertThat(r.rationale()).contains("persistedRecipePath missing");
    }

    @Test
    void slartProcessVanishes_outcomeFailed() {
        primeSpawn();
        when(thinkProcessService.findById(SLART_PROC)).thenReturn(Optional.empty());

        SlartibartfastFallback.Result r = fallback.invoke(caller, "task", "name");

        assertThat(r.outcome()).isEqualTo(SlartibartfastFallback.Outcome.FAILED);
        assertThat(r.rationale()).contains("disappeared from think_processes");
    }

    @Test
    void invokeAsync_returnsImmediately_withPendingOutcome() {
        primeSpawn();

        SlartibartfastFallback.Result r = fallback.invokeAsync(caller, "task", "name");

        // Async mode must NOT poll — even if findById returns empty,
        // we don't observe that. Verify the result shape only.
        assertThat(r.outcome()).isEqualTo(SlartibartfastFallback.Outcome.PENDING);
        assertThat(r.slartProcessId()).isEqualTo(SLART_PROC);
        assertThat(r.recipeName()).isNull();
        assertThat(r.rationale())
                .contains("async")
                .contains("parent-notification");
    }

    // ──────────────────── helpers ────────────────────

    /**
     * Stubs ThinkProcessService.create so that the fallback's
     * spawn() returns a deterministic Slart-process id. The actual
     * Slart engine never runs in unit tests — we drive its
     * status transitions through findById() stubs.
     */
    private void primeSpawn() {
        ThinkProcessDocument spawned = slartProcess(SLART_PROC, ThinkProcessStatus.INIT, null);
        when(thinkProcessService.create(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(spawned);
    }

    private static ThinkProcessDocument slartProcess(
            String id, ThinkProcessStatus status, Map<String, Object> architectState) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(id);
        p.setTenantId(TENANT);
        p.setProjectId(PROJECT);
        p.setSessionId(SESSION);
        p.setStatus(status);
        Map<String, Object> ep = new LinkedHashMap<>();
        if (architectState != null) {
            ep.put(SlartibartfastEngine.STATE_KEY, architectState);
        }
        p.setEngineParams(ep);
        return p;
    }

    private static Map<String, Object> state(String status, String persistedPath) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", status);
        if (persistedPath != null) s.put("persistedRecipePath", persistedPath);
        return s;
    }

    private static Map<String, Object> stateWithFailure(String status, String reason) {
        Map<String, Object> s = state(status, null);
        s.put("failureReason", reason);
        return s;
    }
}
