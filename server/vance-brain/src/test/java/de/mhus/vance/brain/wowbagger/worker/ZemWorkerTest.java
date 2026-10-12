package de.mhus.vance.brain.wowbagger.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemAnswerType;
import de.mhus.vance.api.zem.ZemDecisionDto;
import de.mhus.vance.brain.ai.decision.AiDecisionConfig;
import de.mhus.vance.brain.ai.decision.DecisionModelInfo;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.zem.ZemDecideRequest;
import de.mhus.vance.brain.zem.ZemException;
import de.mhus.vance.brain.zem.ZemPreparedCall;
import de.mhus.vance.brain.zem.ZemService;
import de.mhus.vance.brain.zem.ZemUsageAccumulator;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ZemWorkerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ZemService zem;
    private ZemUsageAccumulator usage;
    private ZemPreparedCall call;
    private ResolvedRecipe recipe;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        zem = mock(ZemService.class);
        usage = mock(ZemUsageAccumulator.class);
        call = new ZemPreparedCall(
                "acme",
                "p1",
                "proc-1",
                null,
                "ticket-triage",
                "default:decision",
                new AiDecisionConfig("ollama", "ollama", "nimble", null, null, 30),
                new DecisionModelInfo("ollama", "nimble", 30, 64, 26, 10, false, null, 0.0, null, 1),
                List.of(),
                List.of(),
                3,
                1,
                null);
        when(zem.prepare(any(ZemDecideRequest.class))).thenReturn(call);
        when(zem.openAccumulator(call)).thenReturn(usage);
        recipe = mock(ResolvedRecipe.class);
        when(recipe.name()).thenReturn("ticket-triage");
        when(recipe.engine()).thenReturn("zem");
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("p1");
    }

    private PreparedWorker prepare(boolean jsonl, boolean merge) {
        return new ZemWorker(zem, MAPPER)
                .prepare(new WorkerSetup(
                        process,
                        new WorkerSpec("ticket-triage", "ignored", null, jsonl, merge, null, null, 3, null),
                        recipe));
    }

    private static ZemDecisionDto decision(String choice) {
        return ZemDecisionDto.builder()
                .model("nimble")
                .answers(List.of(ZemAnswerDto.builder()
                        .name("intent")
                        .type(ZemAnswerType.CHOICE)
                        .choice(choice)
                        .confidence(0.9)
                        .probabilities(Map.of(choice, 0.95, "other", 0.05))
                        .build()))
                .inputTokens(40L)
                .latencyMs(100)
                .build();
    }

    @Test
    void selects_zem_profiles_and_prepares_the_profile_once() {
        assertThat(new ZemWorker(zem, MAPPER).supports(recipe)).isTrue();

        PreparedWorker worker = prepare(true, false);

        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        verify(zem).prepare(request.capture());
        assertThat(request.getValue().getProfile()).isEqualTo("ticket-triage");
        assertThat(request.getValue().getProcessId()).isEqualTo("proc-1");
        assertThat(worker.kind()).isEqualTo("zem");
        assertThat(worker.resolvedModel()).isEqualTo("ollama:nimble");
        assertThat(worker.usesTask()).isFalse();
    }

    @Test
    void jsonl_records_are_decided_as_objects_one_line_each_without_distribution() throws Exception {
        when(zem.decide(eq(call), eq(Map.of("message", "money back")), eq(usage)))
                .thenReturn(decision("refund"));
        when(zem.decide(eq(call), eq(Map.of("message", "broken")), eq(usage))).thenReturn(decision("damaged"));

        WorkerReply reply = prepare(true, false)
                .process(List.of("{\"message\":\"money back\"}", "{\"message\":\"broken\"}"), new ChunkContext(0, 0));

        assertThat(reply.lines()).hasSize(2);
        assertThat(MAPPER.readValue(reply.lines().get(0), Map.class))
                .isEqualTo(Map.of("intent", Map.of("type", "choice", "choice", "refund", "confidence", 0.9)));
        assertThat(reply.calls()).isEqualTo(2);
        assertThat(reply.tokensIn()).isEqualTo(80);
    }

    @Test
    void merge_hangs_the_answers_into_the_record() throws Exception {
        when(zem.decide(eq(call), any(), eq(usage))).thenReturn(decision("refund"));

        WorkerReply reply =
                prepare(true, true).process(List.of("{\"id\":7,\"message\":\"x\"}"), new ChunkContext(0, 0));

        Map<?, ?> line = MAPPER.readValue(reply.lines().get(0), Map.class);
        assertThat(line.get("id")).isEqualTo(7);
        assertThat(((Map<?, ?>) line.get("_zem")).containsKey("intent")).isTrue();
    }

    @Test
    void plain_lines_are_decided_as_text() {
        when(zem.decide(eq(call), eq("charged twice"), eq(usage))).thenReturn(decision("refund"));

        WorkerReply reply = prepare(false, true).process(List.of("charged twice"), new ChunkContext(0, 0));

        assertThat(reply.lines().get(0)).contains("\"refund\"").doesNotContain("_zem");
    }

    @Test
    void a_failed_decision_fails_the_chunk_naming_the_record() {
        when(zem.decide(eq(call), any(), eq(usage)))
                .thenThrow(new ZemException(ZemException.Reason.RATE_LIMITED, "slow down"));

        assertThatThrownBy(() -> prepare(false, false).process(List.of("a", "b"), new ChunkContext(4, 200)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("record 200")
                .hasMessageContaining("[rate_limited]");
    }

    @Test
    void usage_is_booked_at_commit() {
        prepare(false, false).onCommit();

        verify(zem).flush(usage);
    }

    @Test
    void an_unusable_profile_is_refused_at_prepare() {
        when(zem.prepare(any(ZemDecideRequest.class)))
                .thenThrow(new ZemException(ZemException.Reason.INVALID_CHOICE, "no decision model"));

        assertThatThrownBy(() -> prepare(false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'ticket-triage' [invalid_choice]");
    }
}
