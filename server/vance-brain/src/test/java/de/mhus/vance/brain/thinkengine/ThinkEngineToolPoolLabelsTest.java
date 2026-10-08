package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The per-process switch over the engine's tool pool. */
class ThinkEngineToolPoolLabelsTest {

    private final ThinkEngine worker = mock(ThinkEngine.class);

    @Test
    void enginePool_appliesByDefault() {
        when(worker.toolPoolLabels()).thenReturn(Set.of(ToolLabels.WORKER));

        assertThat(ThinkEngineService.toolPoolLabels(process(null), worker)).containsExactly(ToolLabels.WORKER);
    }

    @Test
    void explicitFalse_switchesThePoolOff() {
        when(worker.toolPoolLabels()).thenReturn(Set.of(ToolLabels.WORKER));

        assertThat(ThinkEngineService.toolPoolLabels(process(Boolean.FALSE), worker))
                .isEmpty();
        // YAML params may arrive as strings.
        assertThat(ThinkEngineService.toolPoolLabels(process("false"), worker)).isEmpty();
    }

    @Test
    void anyOtherValue_keepsTheEngineDefault() {
        when(worker.toolPoolLabels()).thenReturn(Set.of(ToolLabels.WORKER));

        assertThat(ThinkEngineService.toolPoolLabels(process(Boolean.TRUE), worker))
                .containsExactly(ToolLabels.WORKER);
    }

    @Test
    void engineWithoutPool_staysWithout() {
        when(worker.toolPoolLabels()).thenReturn(Set.of());

        assertThat(ThinkEngineService.toolPoolLabels(process(Boolean.TRUE), worker))
                .isEmpty();
    }

    private static ThinkProcessDocument process(Object flag) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        Map<String, Object> params = new HashMap<>();
        if (flag != null) params.put(ThinkEngine.PARAM_TOOL_POOL, flag);
        p.setEngineParams(params);
        return p;
    }
}
