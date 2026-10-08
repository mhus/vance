package de.mhus.vance.brain.thinkengine.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shared tool-loop safety nets: they measure being stuck, never volume —
 * idle-stuck over identical batches (status polls exempt), the opt-in round
 * cap, the per-turn wallclock — and the limits resolve from properties plus
 * recipe params with typos falling back instead of disabling a net.
 */
class ToolLoopSafetyNetTest {

    private final ThinkProcessService processes = mock(ThinkProcessService.class);

    private static LoopLimits limits(int maxIterations, int wallclockMinutes, int idleStuck) {
        return new LoopLimits(maxIterations, wallclockMinutes, idleStuck, 0, 0);
    }

    private static List<ToolExecutionRequest> batch(String name, String args) {
        return List.of(ToolExecutionRequest.builder()
                .id("c")
                .name(name)
                .arguments(args)
                .build());
    }

    @Test
    void isIdleStuck_needsAFullWindowOfIdenticalBatches() {
        Deque<String> window = new ArrayDeque<>();
        assertThat(ToolLoopSafetyNet.isIdleStuck(window, "a", 3)).isFalse();
        assertThat(ToolLoopSafetyNet.isIdleStuck(window, "a", 3)).isFalse();
        assertThat(ToolLoopSafetyNet.isIdleStuck(window, "b", 3)).isFalse();
        assertThat(ToolLoopSafetyNet.isIdleStuck(window, "b", 3)).isFalse();
        assertThat(ToolLoopSafetyNet.isIdleStuck(window, "b", 3)).isTrue();
        assertThat(ToolLoopSafetyNet.isIdleStuck(new ArrayDeque<>(), "a", 0)).isFalse();
    }

    @Test
    void onToolBatch_stopsOnRepeats_butNotOnVaryingArguments() {
        ToolLoopSafetyNet net = new ToolLoopSafetyNet(limits(0, 60, 3), processes, "p");
        assertThat(net.onToolBatch(batch("doc_read", "{\"n\":1}"), "")).isNull();
        assertThat(net.onToolBatch(batch("doc_read", "{\"n\":2}"), "")).isNull();
        assertThat(net.onToolBatch(batch("doc_read", "{\"n\":3}"), "")).isNull();
        assertThat(net.onToolBatch(batch("doc_read", "{\"n\":3}"), "")).isNull();
        SafetyStop stop = net.onToolBatch(batch("doc_read", "{\"n\":3}"), "partial");
        assertThat(stop).isNotNull();
        assertThat(stop.reason()).isEqualTo(SafetyStop.SafetyStopReason.IDLE_STUCK);
        assertThat(stop.partialText()).isEqualTo("partial");
    }

    @Test
    void onToolBatch_exemptsStatusPolls_includingEngineSpecificOnes() {
        ToolLoopSafetyNet net =
                new ToolLoopSafetyNet(limits(0, 60, 2), processes, "p", Set.of("exec_status", "wowbagger_status"));
        for (int i = 0; i < 5; i++) {
            assertThat(net.onToolBatch(batch("wowbagger_status", "{}"), "")).isNull();
            net.afterToolBatch(batch("wowbagger_status", "{}")); // step 0 → no sleep
        }
    }

    @Test
    void beforeRound_roundCapIsOptIn() {
        ToolLoopSafetyNet uncapped = new ToolLoopSafetyNet(limits(0, 60, 5), processes, "p");
        assertThat(uncapped.beforeRound(10_000, "")).isNull();

        ToolLoopSafetyNet capped = new ToolLoopSafetyNet(limits(2, 60, 5), processes, "p");
        assertThat(capped.beforeRound(1, "")).isNull();
        SafetyStop stop = capped.beforeRound(2, "");
        assertThat(stop).isNotNull();
        assertThat(stop.reason()).isEqualTo(SafetyStop.SafetyStopReason.ITERATION_LIMIT);
    }

    @Test
    void beforeRound_zeroWallclockTripsAtOnce() {
        ToolLoopSafetyNet net = new ToolLoopSafetyNet(limits(0, 0, 5), processes, "p");
        SafetyStop stop = net.beforeRound(0, "");
        assertThat(stop).isNotNull();
        assertThat(stop.reason()).isEqualTo(SafetyStop.SafetyStopReason.WALLCLOCK);
    }

    @Test
    void stopTexts_areNeverBlank_andCarryThePartialWork() {
        SafetyStop stop = SafetyStop.emptyReply("half done");
        assertThat(stop.workerText()).startsWith("⚠️ TASK FAILED").contains("Partial progress:\n\nhalf done");
        assertThat(stop.continuableText())
                .startsWith("⚠️ I stopped this turn")
                .contains("\"continue\"")
                .contains("Progress so far:\n\nhalf done");
        assertThat(SafetyStop.llmFailure(new IllegalStateException(), "").describe())
                .contains("IllegalStateException");
    }

    @Test
    void loopLimits_defaultsHaveNoRoundCap_andIgnoreTypos() {
        EngineLoopProperties properties = new EngineLoopProperties();
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setEngineParams(new LinkedHashMap<>());
        assertThat(LoopLimits.resolve(p, properties).maxIterations()).isZero();
        assertThat(LoopLimits.resolve(p, properties).wallclockMinutes()).isEqualTo(properties.getMaxWallclockMinutes());

        p.getEngineParams().put(LoopLimits.PARAM_MAX_WALLCLOCK_MINUTES, "-5");
        p.getEngineParams().put(LoopLimits.PARAM_MAX_ITERATIONS, "abc");
        assertThat(LoopLimits.resolve(p, properties).wallclockMinutes()).isEqualTo(properties.getMaxWallclockMinutes());
        assertThat(LoopLimits.resolve(p, properties).maxIterations()).isZero();

        p.getEngineParams().put(LoopLimits.PARAM_MAX_ITERATIONS, 3);
        assertThat(LoopLimits.resolve(p, properties).maxIterations()).isEqualTo(3);
    }
}
