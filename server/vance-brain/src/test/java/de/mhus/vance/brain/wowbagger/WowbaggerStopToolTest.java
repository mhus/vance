package de.mhus.vance.brain.wowbagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code wowbagger_stop} — the park knob the prompt and the engine status
 * block name; added because the taught surface promised a tool that did not
 * exist (found during the M1 fix, Code-Review 15).
 */
class WowbaggerStopToolTest {

    private static final String PROC_ID = "proc-1";

    @Test
    void parksThePoolAndReportsTheIntactStructure() {
        ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
        WowbaggerPoolService pool = mock(WowbaggerPoolService.class);
        WowbaggerStopTool tool = new WowbaggerStopTool(thinkProcessService, pool);

        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setTenantId("t1");
        process.setProjectId("p1");
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
        WowbaggerState state = new WowbaggerState();
        state.setThreadsDesired(4);
        when(pool.stop(PROC_ID)).thenReturn(new WowbaggerPoolService.RunView(state, false, 0, null));

        Map<String, Object> out = tool.invoke(Map.of(), new ToolInvocationContext("t1", "p1", "s1", PROC_ID, "marvin"));

        verify(pool).stop(PROC_ID);
        assertThat(out.get("stopped")).isEqualTo(true);
        assertThat(out.get("running")).isEqualTo(false);
        assertThat(out.get("threadsDesired")).isEqualTo(4);
        assertThat((String) out.get("note")).contains("wowbagger_start resumes");
    }
}
