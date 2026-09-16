package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.brain.wowbagger.WowbaggerEngine;
import de.mhus.vance.brain.wowbagger.WowbaggerPoolService;
import de.mhus.vance.brain.wowbagger.WowbaggerState;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code //wowbagger info} diagnostic: read-only, no lane, the full
 * run picture without waking the agent (specification/public/wowbagger-engine.md §7).
 */
class WowbaggerCommandHandlerTest {

    private WowbaggerPoolService pool;
    private WowbaggerCommandHandler handler;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        pool = mock(WowbaggerPoolService.class);
        handler = new WowbaggerCommandHandler(pool);
        process = new ThinkProcessDocument();
        process.setId("proc1");
        process.setName("bulk-run");
        process.setThinkEngine(WowbaggerEngine.NAME);
    }

    private WowbaggerState configuredState() {
        WowbaggerState s = new WowbaggerState();
        s.setTask("classify each record into one category");
        s.setSourcePath("input.jsonl");
        s.setInputFormat("jsonl");
        s.setOutputFormat("jsonl");
        s.setChunkSize(50);
        s.setThreadsDesired(4);
        s.setWakeEveryRecords(1000);
        s.setWakeEverySeconds(1800);
        s.setFailureCooldownSeconds(300);
        s.setRecordsTotal(9500);
        s.setChunksTotal(190);
        s.setPointer(700);
        s.setRecordsDone(600);
        s.setResolvedWorkerModel("coding-proxy:some-model");
        WowbaggerState.WaveChunk failed = new WowbaggerState.WaveChunk();
        failed.setIndex(17);
        failed.setStartRecord(850);
        failed.setRecordCount(50);
        failed.setAttempts(4);
        failed.setLastError("LightLlmException: provider down");
        s.getFailedChunks().add(failed);
        s.setFailureCount(3);
        s.setOutputDocPath("_wowbagger/proc1/result.jsonl");
        return s;
    }

    @Test
    void infoRendersTheFullRunPicture() {
        WowbaggerState s = configuredState();
        when(pool.structure("proc1")).thenReturn(s);
        when(pool.isRunning("proc1")).thenReturn(true);
        lenient().when(pool.isWorkerModelApproved(any(), any())).thenReturn(true);

        EngineCommandResult result = handler.handle(process, new EngineCommand("wowbagger", Map.of("text", "")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message())
                .contains("RUNNING")
                .contains("4 thread(s) desired")
                .contains("600/9500 records")
                .contains("pointer at 700")
                .contains("coding-proxy:some-model")
                .contains("approved: true")
                .contains("#17 (records 850–899, 4 attempts)")
                .contains("provider down")
                .contains("3 unacked failure(s)")
                .contains("_wowbagger/proc1/result.jsonl");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertThat(value).containsEntry("recordsDone", 600L).containsEntry("failureCount", 3L);
        assertThat(handler.runsOnLane()).isFalse();
        assertThat(handler.verb()).isEqualTo("wowbagger");
    }

    @Test
    void unconfiguredRunSaysSoInsteadOfNPEing() {
        when(pool.structure("proc1")).thenReturn(new WowbaggerState());
        when(pool.isRunning("proc1")).thenReturn(false);
        lenient().when(pool.isWorkerModelApproved(any(), any())).thenReturn(false);

        EngineCommandResult result = handler.handle(process, new EngineCommand("wowbagger", Map.of("text", "info")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message())
                .contains("idle")
                .contains("(not set — the agent has not configured the run yet)")
                .contains("(not resolved yet)")
                .contains("approved: false");
    }

    @Test
    void foreignEngineIsADefinedError() {
        process.setThinkEngine("ford");
        EngineCommandResult result = handler.handle(process, new EngineCommand("wowbagger", Map.of()));
        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("needs a Wowbagger process");
    }

    @Test
    void unknownSubcommandIsADefinedError() {
        when(pool.structure("proc1")).thenReturn(new WowbaggerState());
        when(pool.isRunning("proc1")).thenReturn(false);
        lenient().when(pool.isWorkerModelApproved(any(), any())).thenReturn(false);
        EngineCommandResult result = handler.handle(process, new EngineCommand("wowbagger", Map.of("text", "nuke")));
        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("Unknown subcommand 'nuke'");
    }
}
