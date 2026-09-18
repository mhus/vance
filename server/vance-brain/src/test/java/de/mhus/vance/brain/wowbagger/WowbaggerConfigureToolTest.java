package de.mhus.vance.brain.wowbagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The configure tool's running-guard (Code-Review 15, M1): a running pool
 * is REFUSED, never silently parked. The original code called
 * {@code pool.stop()} before checking {@code running} — unreachable, since
 * stop() flips the flag synchronously — so every configure, down to a bare
 * {@code resetFailureCount}, quietly killed the rotation.
 */
class WowbaggerConfigureToolTest {

    private static final String PROC_ID = "proc-1";

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final WowbaggerPoolService pool = mock(WowbaggerPoolService.class);
    private final WowbaggerConfigureTool tool = new WowbaggerConfigureTool(thinkProcessService, pool);

    private final ThinkProcessDocument process = process();

    private static ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(PROC_ID);
        p.setTenantId("t1");
        p.setProjectId("p1");
        return p;
    }

    private ToolInvocationContext ctx() {
        return new ToolInvocationContext("t1", "p1", "s1", PROC_ID, "marvin");
    }

    @Test
    void refusesWhileThePoolIsRunningInsteadOfSilentlyParkingIt() {
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
        when(pool.isRunning(PROC_ID)).thenReturn(true);

        assertThatThrownBy(() -> tool.invoke(Map.of("task", "classify"), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("the pool is running")
                .hasMessageContaining("wowbagger_stop");

        // The refusal must not touch the pool at all — no stop, no persist.
        verify(pool, never()).stop(anyString());
        verify(pool, never()).persistStructure(any(), any());
        verify(pool, never()).setThreadsDesired(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void appliesToThePersistedStructureWhenParked() {
        WowbaggerState state = new WowbaggerState();
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
        when(pool.isRunning(PROC_ID)).thenReturn(false);
        when(pool.structure(PROC_ID)).thenReturn(state);
        when(pool.ensureWorkRoot(process, state)).thenReturn("wowbagger-run");
        when(pool.sourceRootWarnings(process, state)).thenReturn(List.of());

        Map<String, Object> out = tool.invoke(Map.of("task", "classify each record"), ctx());

        assertThat(out.get("applied")).isEqualTo(Map.of("task", "classify each record"));
        assertThat(out.get("workTarget")).isEqualTo("wowbagger-run");
        // One serialization path: the tool writes through the pool, and the
        // model gate ran before the persist.
        verify(pool).checkModelApproval(process, state);
        verify(pool).persistStructure(process, state);
        assertThat(state.getTask()).isEqualTo("classify each record");
    }
}
