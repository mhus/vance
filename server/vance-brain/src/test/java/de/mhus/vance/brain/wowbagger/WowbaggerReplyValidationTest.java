package de.mhus.vance.brain.wowbagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the worker-reply contract (§ Fehler-Politik): exactly one output
 * line per input record, no blanks, and for JSONL every line a JSON
 * object — a structurally wrong reply is a failed attempt, not data.
 * The validation lives in the pool (workers call it); it is the same
 * contract the agent's wowbagger_configure outputFormat implies.
 */
class WowbaggerReplyValidationTest {

    private final ObjectMapper om = JsonMapper.builder().build();

    @Test
    void happyPathReturnsTrimmedLines() {
        List<String> lines = WowbaggerPoolService.validateWorkerReply(" a \n b \n c ", 3, false, om);
        assertThat(lines).containsExactly("a", "b", "c");
    }

    @Test
    void blankLinesAreDroppedNotCounted() {
        List<String> lines = WowbaggerPoolService.validateWorkerReply("a\n\nb\n", 2, false, om);
        assertThat(lines).containsExactly("a", "b");
    }

    @Test
    void countMismatchIsAFailedAttempt() {
        assertThatThrownBy(() -> WowbaggerPoolService.validateWorkerReply("a\nb", 3, false, om))
                .isInstanceOf(WowbaggerPoolService.WorkerReplyException.class)
                .hasMessageContaining("expected 3 output lines, got 2");
    }

    @Test
    void jsonlRequiresParsableObjectsPerLine() {
        List<String> lines = WowbaggerPoolService.validateWorkerReply("{\"ok\":true}\n{\"ok\":false}", 2, true, om);
        assertThat(lines).hasSize(2);
        assertThatThrownBy(() -> WowbaggerPoolService.validateWorkerReply("{\"ok\":true}\nnot json", 2, true, om))
                .isInstanceOf(WowbaggerPoolService.WorkerReplyException.class)
                .hasMessageContaining("not a JSON object");
    }

    @Test
    void jsonArraysAreNotObjects() {
        assertThatThrownBy(() -> WowbaggerPoolService.validateWorkerReply("[1,2]", 1, true, om))
                .isInstanceOf(WowbaggerPoolService.WorkerReplyException.class)
                .hasMessageContaining("not a JSON object");
    }

    @Test
    void chunkDocPathUsesRunFolderAndFormatSuffix() {
        assertThat(WowbaggerPoolService.chunkDocPath("proc1", 0, "jsonl"))
                .isEqualTo("_wowbagger/proc1/results/chunk-000000.jsonl");
        assertThat(WowbaggerPoolService.chunkDocPath("proc1", 42, "lines"))
                .isEqualTo("_wowbagger/proc1/results/chunk-000042.txt");
    }

    @Test
    void stateRoundTripsThroughTheEngineParamsMapForm() {
        ObjectMapper mapper = JsonMapper.builder().build();
        WowbaggerState state = new WowbaggerState();
        state.setTask("classify");
        state.setSourcePath("input.jsonl");
        state.setThreadsDesired(4);
        state.setWakeEveryRecords(1000);
        state.setChunkSize(50);
        state.setRecordsTotal(120);
        state.setChunksTotal(3);
        state.setPointer(40);
        state.setRecordsDone(20);
        WowbaggerState.WaveChunk chunk = new WowbaggerState.WaveChunk();
        chunk.setIndex(1);
        chunk.setStartRecord(50);
        chunk.setRecordCount(10);
        chunk.setAttempts(2);
        chunk.setLastError("boom");
        state.getWave().add(chunk);
        state.getFailedChunks().add(chunk);
        state.setWorkerRecipe("wowbagger-worker-sipgate");
        state.setFinished(false);

        // The engineParams persistence form: state → Map → state.
        Map<String, Object> raw = mapper.convertValue(state, Map.class);
        WowbaggerState back = WowbaggerPoolService.normalize(mapper.convertValue(raw, WowbaggerState.class));

        assertThat(back.getTask()).isEqualTo("classify");
        assertThat(back.getWorkerRecipe()).isEqualTo("wowbagger-worker-sipgate");
        assertThat(back.getThreadsDesired()).isEqualTo(4);
        assertThat(back.getPointer()).isEqualTo(40);
        assertThat(back.getRecordsDone()).isEqualTo(20);
        assertThat(back.getWave()).hasSize(1);
        assertThat(back.getWave().getFirst().getIndex()).isEqualTo(1);
        assertThat(back.getWave().getFirst().getAttempts()).isEqualTo(2);
        assertThat(back.getWave().getFirst().getLastError()).isEqualTo("boom");
        assertThat(back.getFailedChunks()).hasSize(1);
        assertThat(back.isFinished()).isFalse();
    }

    @Test
    void mapKeyVocabularyMatchesTheEngineConstants() {
        // The serialized keys are the persisted contract — renaming a field
        // breaks resume of running processes (Zaphod lesson). Pin the set.
        ObjectMapper mapper = JsonMapper.builder().build();
        Map<String, Object> raw = mapper.convertValue(new WowbaggerState(), Map.class);
        assertThat(raw)
                .containsKeys(
                        "task",
                        "sourcePath",
                        "inputFormat",
                        "outputFormat",
                        "outputDocPath",
                        "chunkSize",
                        "threadsDesired",
                        "wakeEveryRecords",
                        "chunkRetries",
                        "maxTokens",
                        "workerRecipe",
                        "pointer",
                        "recordsTotal",
                        "chunksTotal",
                        "recordsDone",
                        "wave",
                        "retryQueue",
                        "failedChunks",
                        "counters",
                        "finished");
    }

    @Test
    void modelApprovedMatchesWildcardsCommaAndBareNames() {
        // Full instance:model form, bare model name, wildcards, commas, case.
        assertThat(WowbaggerPoolService.modelApproved("openai:deepseek-v4-flash-0731", "*deepseek*"))
                .isTrue();
        assertThat(WowbaggerPoolService.modelApproved("coding-proxy:sipgate-coding-pro", "sipgate-coding-pro"))
                .isTrue();
        assertThat(WowbaggerPoolService.modelApproved("coding-proxy:sipgate-coding-pro", "coding-proxy:*"))
                .isTrue();
        assertThat(WowbaggerPoolService.modelApproved("coding-proxy:sipgate-coding-pro", "*SIPGATE*"))
                .isTrue();
        assertThat(WowbaggerPoolService.modelApproved("openai:gpt-x", "*deepseek*, *cheap*, openai:gpt-x"))
                .isTrue();
    }

    @Test
    void modelApprovedIsFailClosed() {
        // No allowlist → nothing approved (cost gate, § Model approval).
        assertThat(WowbaggerPoolService.modelApproved("openai:cheap-model", null))
                .isFalse();
        assertThat(WowbaggerPoolService.modelApproved("openai:cheap-model", "  "))
                .isFalse();
        assertThat(WowbaggerPoolService.modelApproved("openai:gpt-expensive", "*deepseek*, sipgate-coding-pro"))
                .isFalse();
        // Bare "*" approves everything — the operator's explicit opt-out.
        assertThat(WowbaggerPoolService.modelApproved("openai:anything", "*")).isTrue();
        // Empty pattern segments are skipped, not matched.
        assertThat(WowbaggerPoolService.modelApproved("openai:x", ",,")).isFalse();
    }

    @Test
    void describeErrorSurfacesTheDeepestCause() {
        // Live-run lesson: "All 1 chat-model chain entries exhausted" alone
        // read as "provider down" — the real cause (a provider 400: max_tokens
        // clamped to the context window) was three causes down. The ledger
        // must show it.
        RuntimeException root = new RuntimeException(
                "This model's maximum context length is 512000 tokens. However, you requested 512000 output tokens");
        RuntimeException chain = new RuntimeException("All 1 chat-model chain entries exhausted", root);
        var llm = new de.mhus.vance.brain.ai.light.LightLlmException("LLM call failed", chain);

        String described = WowbaggerPoolService.describeError(llm);
        assertThat(described)
                .contains("LightLlmException")
                .contains("root cause")
                .contains("maximum context length is 512000")
                .doesNotContain("entries exhausted — root cause: All");
        // No cause chain → no invented root cause.
        assertThat(WowbaggerPoolService.describeError(new RuntimeException("source lost")))
                .isEqualTo("java.lang.RuntimeException: source lost");
    }

    @Test
    void normalizeNeverInventsState() {
        // A pre-pivot persisted map (missing retryQueue/finished) loads
        // tolerantly — the Zaphod lesson.
        ObjectMapper mapper = JsonMapper.builder().build();
        Map<String, Object> legacy = Map.of(
                "task", "t",
                "pointer", 5L,
                "recordsTotal", 10L);
        WowbaggerState state = WowbaggerPoolService.normalize(mapper.convertValue(legacy, WowbaggerState.class));
        assertThat(state.getTask()).isEqualTo("t");
        assertThat(state.getPointer()).isEqualTo(5);
        assertThat(state.getRetryQueue()).isEmpty();
        assertThat(state.getChunkSize()).isEqualTo(50); // field default
    }
}
