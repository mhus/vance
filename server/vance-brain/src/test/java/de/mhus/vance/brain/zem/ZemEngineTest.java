package de.mhus.vance.brain.zem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemAnswerType;
import de.mhus.vance.api.zem.ZemDecisionDto;
import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ZemEngineTest {

    private static final String PROC_ID = "proc-zem-1";

    private ZemService zemService;
    private ThinkProcessService thinkProcessService;
    private ChatMessageService chatMessageService;
    private ThinkEngineContext ctx;
    private ZemEngine engine;
    private ThinkProcessDocument process;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        zemService = mock(ZemService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        chatMessageService = mock(ChatMessageService.class);
        ctx = mock(ThinkEngineContext.class);
        lenient().when(ctx.chatMessageService()).thenReturn(chatMessageService);
        lenient().when(chatMessageService.append(any())).thenAnswer(inv -> inv.getArgument(0));
        engine = new ZemEngine(zemService, thinkProcessService, mapper);

        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        process.setProjectId("proj-1");
        process.setRecipeName("zem");
        process.setStatus(ThinkProcessStatus.INIT);
        process.setEngineParams(new LinkedHashMap<>());
    }

    private static ZemDecisionDto decision() {
        return ZemDecisionDto.builder()
                .model("typesafe/jev-1.13-20260917")
                .answers(List.of(
                        ZemAnswerDto.builder()
                                .name("unsafe")
                                .type(ZemAnswerType.NOUL)
                                .noul(0.97)
                                .confidence(0.94)
                                .build(),
                        ZemAnswerDto.builder()
                                .name("intent")
                                .type(ZemAnswerType.CHOICE)
                                .choice("refund")
                                .probabilities(Map.of("refund", 0.9, "other", 0.1))
                                .confidence(0.8)
                                .build()))
                .latencyMs(420)
                .build();
    }

    @Test
    void decision_is_written_as_wrapper_stored_compactly_and_the_process_closes_done() throws Exception {
        process.getEngineParams().put("state", Map.of("command", "rm -rf /data"));
        process.getEngineParams().put("profile", "command-safety");
        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        when(zemService.decide(request.capture(), eq(Map.of("command", "rm -rf /data"))))
                .thenReturn(decision());

        engine.start(process, ctx);

        assertThat(request.getValue().getProfile()).isEqualTo("command-safety");
        assertThat(request.getValue().getProcessId()).isEqualTo(PROC_ID);

        ArgumentCaptor<ChatMessageDocument> messages = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService, times(2)).append(messages.capture());
        assertThat(messages.getAllValues().get(0).getRole()).isEqualTo(ChatRole.USER);
        assertThat(messages.getAllValues().get(0).getContent()).contains("rm -rf /data");
        Map<?, ?> wrapper = mapper.readValue(messages.getAllValues().get(1).getContent(), Map.class);
        assertThat(wrapper.get("success")).isEqualTo(true);
        assertThat(((Map<?, ?>) wrapper.get("data")).get("model")).isEqualTo("typesafe/jev-1.13-20260917");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> stored = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).replaceEngineParams(eq(PROC_ID), stored.capture());
        Map<?, ?> outcome = (Map<?, ?>) stored.getValue().get(ZemEngine.PARAM_OUTCOME);
        assertThat(((Map<?, ?>) outcome.get("answers")).get("intent"))
                .isEqualTo(Map.of("type", "choice", "choice", "refund", "confidence", 0.8));
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    @Test
    void inline_questions_from_the_params_reach_the_service() {
        process.getEngineParams().put("state", "charged twice");
        process.getEngineParams().put("model", "ollama:nimble");
        process.getEngineParams()
                .put("questions", List.of(Map.of("name", "billing", "type", "noul", "instructions", "Billing?")));
        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        when(zemService.decide(request.capture(), any())).thenReturn(decision());

        engine.start(process, ctx);

        assertThat(request.getValue().getProfile()).isNull(); // spawned from the generic 'zem' recipe
        assertThat(request.getValue().getModel()).isEqualTo("ollama:nimble");
        assertThat(request.getValue().getQuestions())
                .extracting(ZemQuestionDto::getName)
                .containsExactly("billing");
    }

    @Test
    void spawned_from_a_profile_recipe_the_recipe_is_the_profile() {
        process.setRecipeName("command-safety");
        process.getEngineParams().put("state", "x");
        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        when(zemService.decide(request.capture(), any())).thenReturn(decision());

        engine.start(process, ctx);

        assertThat(request.getValue().getProfile()).isEqualTo("command-safety");
    }

    @Test
    void service_failure_becomes_a_failure_wrapper_and_still_closes_done() throws Exception {
        process.getEngineParams().put("state", "x");
        when(zemService.decide(any(), any()))
                .thenThrow(new ZemException(ZemException.Reason.RATE_LIMITED, "slow down"));

        engine.start(process, ctx);

        ArgumentCaptor<ChatMessageDocument> messages = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService, times(2)).append(messages.capture());
        Map<?, ?> wrapper = mapper.readValue(messages.getAllValues().get(1).getContent(), Map.class);
        assertThat(wrapper.get("success")).isEqualTo(false);
        assertThat(wrapper.get("error")).isEqualTo("rate_limited");
        assertThat(wrapper.get("retryable")).isEqualTo(true);
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    @Test
    void missing_state_fails_without_calling_the_service() {
        engine.start(process, ctx);

        verify(zemService, never()).decide(any(ZemDecideRequest.class), any());
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    @Test
    void parent_report_reads_the_stored_outcome() {
        process.getEngineParams().put(ZemEngine.PARAM_OUTCOME, ZemEngine.compactOutcome(decision()));

        ParentReport report = engine.summarizeForParent(process, ProcessEventType.DONE);

        assertThat(report.humanSummary())
                .contains("Zem decision (typesafe/jev-1.13-20260917)")
                .contains("- unsafe: p(yes)=0.97 (confidence 0.94)")
                .contains("- intent: refund (confidence 0.80)");
        assertThat(report.payload()).containsKey("answers");
    }

    @Test
    void parent_report_without_outcome_says_so() {
        assertThat(engine.summarizeForParent(process, ProcessEventType.STOPPED).humanSummary())
                .contains("without a result");
    }

    @Test
    void stop_writes_a_cancelled_wrapper_and_closes_stopped() {
        engine.stop(process, ctx);

        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.STOPPED);
    }
}
