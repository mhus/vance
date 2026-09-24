package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HactarStatusTool}: the on-demand read of this process' run —
 * live console ring first (mid-run and after the terminal), persisted
 * tail fallback (post-restart), phase/result/error fields, and the
 * consoleLines clamp.
 */
class HactarStatusToolTest {

    private ThinkProcessService thinkProcessService;
    private HactarStateStore stateStore;
    private HactarRunService runService;
    private HactarConsoleLog consoleLog;
    private HactarStatusTool tool;
    private ThinkProcessDocument process;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        runService = mock(HactarRunService.class);
        consoleLog = new HactarConsoleLog();
        stateStore = new HactarStateStore(
                thinkProcessService,
                tools.jackson.databind.json.JsonMapper.builder().build());
        tool = new HactarStatusTool(thinkProcessService, stateStore, runService, consoleLog);

        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        process.setProjectId("proj-1");
        when(thinkProcessService.findById("proc-1")).thenReturn(java.util.Optional.of(process));

        ctx = mock(ToolInvocationContext.class);
        when(ctx.processId()).thenReturn("proc-1");
    }

    /** Puts a state into the process' engine params, the way the run
     *  service persists it (HactarEngine.STATE_KEY). */
    private void persistState(HactarState state) {
        Map<String, Object> params = new HashMap<>();
        params.put(
                HactarEngine.STATE_KEY,
                tools.jackson.databind.json.JsonMapper.builder().build().convertValue(state, Map.class));
        process.setEngineParams(params);
    }

    @Test
    void status_liveConsoleMidRun_withElapsed() {
        persistState(HactarState.builder()
                .status(HactarStatus.EXECUTING)
                .scriptRef("_vance/scripts/hello.js")
                .build());
        when(runService.isRunning("proc-1")).thenReturn(true);
        when(runService.runStartedAtMs("proc-1")).thenReturn(System.currentTimeMillis() - 15_000);
        consoleLog.startRun("proc-1");
        consoleLog.record("proc-1", "[info] Hello, world! #1\n");
        consoleLog.record("proc-1", "[info] Hello, world! #2\n");

        Map<String, Object> out = tool.invoke(Map.of(), ctx);

        assertThat(out.get("running")).isEqualTo(true);
        assertThat(out.get("phase")).isEqualTo("EXECUTING");
        assertThat((Long) out.get("elapsedMs")).isBetween(10_000L, 60_000L);
        assertThat((String) out.get("console")).contains("#1").contains("#2");
    }

    @Test
    void status_afterTerminal_readsKeptRing() {
        persistState(HactarState.builder()
                .status(HactarStatus.DONE)
                .scriptRef("_vance/scripts/hello.js")
                .executionResult("Hello, world!")
                .build());
        when(runService.isRunning("proc-1")).thenReturn(false);
        consoleLog.startRun("proc-1");
        consoleLog.record("proc-1", "[info] Hello, world! #10\n");

        Map<String, Object> out = tool.invoke(Map.of(), ctx);

        assertThat(out.get("running")).isEqualTo(false);
        assertThat(out.get("phase")).isEqualTo("DONE");
        assertThat(out.get("executionResult")).isEqualTo("Hello, world!");
        assertThat((String) out.get("console")).contains("#10");
        assertThat(out).doesNotContainKey("elapsedMs");
    }

    @Test
    void status_fallsBackToPersistedTailWhenRingEmpty() {
        persistState(HactarState.builder()
                .status(HactarStatus.FAILED)
                .failureReason("boom")
                .consoleTail("[info] only line\n")
                .build());
        when(runService.isRunning("proc-1")).thenReturn(false);

        Map<String, Object> out = tool.invoke(Map.of(), ctx);

        assertThat(out.get("failureReason")).isEqualTo("boom");
        assertThat((String) out.get("console")).contains("only line");
    }

    @Test
    void status_consoleLinesClamped() {
        consoleLog.startRun("proc-1");
        for (int i = 1; i <= 50; i++) {
            consoleLog.record("proc-1", "line-" + i + "\n");
        }

        Map<String, Object> capped = tool.invoke(Map.of("consoleLines", 5), ctx);
        String console = (String) capped.get("console");
        assertThat(console.lines().count()).isEqualTo(5);
        assertThat(console).contains("line-50").doesNotContain("line-40");

        Map<String, Object> overMax = tool.invoke(Map.of("consoleLines", 999), ctx);
        assertThat(overMax.get("consoleLinesRequested")).isEqualTo(HactarStatusTool.MAX_CONSOLE_LINES);
        Map<String, Object> negative = tool.invoke(Map.of("consoleLines", -3), ctx);
        assertThat(negative.get("consoleLinesRequested")).isEqualTo(1);
    }

    @Test
    void status_unknownProcess_fails() {
        when(ctx.processId()).thenReturn("nope");
        when(thinkProcessService.findById("nope")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not found");
    }
}
