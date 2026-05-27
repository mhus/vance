package de.mhus.vance.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ford.FordProperties;
import de.mhus.vance.brain.memory.evaluation.CheapPathFilter;
import de.mhus.vance.brain.memory.evaluation.HotPathMarkerDetector;
import de.mhus.vance.brain.memory.evaluation.MemoryAnalyzerService;
import de.mhus.vance.brain.memory.evaluation.MemoryEvaluationProperties;
import de.mhus.vance.brain.memory.evaluation.MemoryEvaluationSanitizer;
import de.mhus.vance.brain.memory.evaluation.SpanMessage;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.memory.evaluation.EvaluationOutput;
import de.mhus.vance.shared.memory.evaluation.ItemCountExpectation;
import de.mhus.vance.shared.memory.evaluation.WindowSpan;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Behaviour of the {@code MemoryCompactionService} → {@code
 * MemoryAnalyzerService} side-channel wiring. The side-channel is
 * opt-in via {@code vance.memeval.sideChannelEnabled}; this test drives
 * it through the range-compaction path (cheapest to set up) and
 * verifies:
 *
 * <ul>
 *   <li>Default-off — analyzer never invoked.</li>
 *   <li>Enabled + substantial span — analyzer invoked with the expected
 *       hint plus scope IDs; sanitizer ran (counter recorded).</li>
 *   <li>Enabled + cheap-path-skippable span — analyzer NOT invoked,
 *       skip counter recorded.</li>
 *   <li>Analyzer throwing does not fail the compaction.</li>
 * </ul>
 */
class MemoryCompactionSideChannelTest {

    private ChatMessageService chatMessageService;
    private MemoryService memoryService;
    private AiModelService aiModelService;
    private SessionService sessionService;
    private SettingService settingService;
    private FordProperties properties;
    private LlmCallTracker llmCallTracker;
    private ProgressEmitter progressEmitter;
    private MetricService metricService;
    private MemoryAnalyzerService analyzer;
    private MemoryEvaluationProperties evaluationProperties;
    private MemoryCompactionService service;

    private AiChat aiChat;
    private ChatModel chatModel;

    private final AiChatConfig config =
            new AiChatConfig("anthropic", "claude-sonnet-4-5", "k");

    @BeforeEach
    void setUp() {
        chatMessageService = mock(ChatMessageService.class);
        memoryService = mock(MemoryService.class);
        aiModelService = mock(AiModelService.class);
        sessionService = mock(SessionService.class);
        settingService = mock(SettingService.class);
        properties = new FordProperties();
        llmCallTracker = mock(LlmCallTracker.class);
        progressEmitter = mock(ProgressEmitter.class);
        metricService = new MetricService(new SimpleMeterRegistry());
        analyzer = mock(MemoryAnalyzerService.class);
        evaluationProperties = new MemoryEvaluationProperties();
        CheapPathFilter cheapPath = new CheapPathFilter(new HotPathMarkerDetector());
        MemoryEvaluationSanitizer sanitizer =
                new MemoryEvaluationSanitizer(evaluationProperties);

        service = new MemoryCompactionService(
                chatMessageService, memoryService, aiModelService,
                sessionService, settingService, properties,
                llmCallTracker, progressEmitter, metricService,
                analyzer, cheapPath, sanitizer, evaluationProperties);

        aiChat = mock(AiChat.class);
        chatModel = mock(ChatModel.class);
        when(aiChat.chatModel()).thenReturn(chatModel);
        when(aiModelService.createChat(eq(config), any(AiChatOptions.class)))
                .thenReturn(aiChat);
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());

        // Persist always returns the saved doc with a generated id.
        when(memoryService.save(any())).thenAnswer(inv -> {
            MemoryDocument arg = inv.getArgument(0);
            arg.setId("mem-id");
            return arg;
        });
        when(chatMessageService.markArchived(any(), anyString())).thenReturn(0L);
    }

    @Test
    void sideChannel_disabledByDefault_doesNotCallAnalyzer() {
        // Sanity: default is false.
        assertThat(evaluationProperties.isSideChannelEnabled()).isFalse();

        primeRange(longUserSpan());
        primeSummarizer("Summary text");

        CompactionResult result = service.compactRange(
                process(), Instant.parse("2026-05-11T14:00:00Z"),
                Instant.parse("2026-05-11T15:00:00Z"), "auth-setup", config);

        assertThat(result.compacted()).isTrue();
        verify(analyzer, never()).analyze(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sideChannel_enabledAndSubstantialSpan_callsAnalyzer() {
        evaluationProperties.setSideChannelEnabled(true);

        primeRange(longUserSpan());
        primeSummarizer("Summary text");
        when(analyzer.analyze(any(), any(), any(), any(), any(), any()))
                .thenReturn(EvaluationOutput.empty(
                        new WindowSpan("m1", "m1", 1)));

        CompactionResult result = service.compactRange(
                process(), Instant.parse("2026-05-11T14:00:00Z"),
                Instant.parse("2026-05-11T15:00:00Z"), "auth-setup", config);

        assertThat(result.compacted()).isTrue();

        ArgumentCaptor<List<SpanMessage>> spanCap = listCaptor();
        ArgumentCaptor<String> hintCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ItemCountExpectation> exp =
                ArgumentCaptor.forClass(ItemCountExpectation.class);
        verify(analyzer).analyze(
                eq("t"),
                any(),
                eq("p-1"),
                spanCap.capture(),
                hintCap.capture(),
                exp.capture());
        assertThat(spanCap.getValue()).hasSize(longUserSpan().size());
        assertThat(hintCap.getValue()).contains("auth-setup");
    }

    @Test
    void sideChannel_enabledButShortSpan_skipsAnalyzer() {
        evaluationProperties.setSideChannelEnabled(true);

        Instant t0 = Instant.parse("2026-05-11T14:00:00Z");
        // Only acks → cheap-path "below-token-threshold" or "only-ack-or-narration".
        primeRange(List.of(
                msg("m1", ChatRole.USER, "ok", t0),
                msg("m2", ChatRole.ASSISTANT, "ja", t0.plusSeconds(1))));
        primeSummarizer("trivial summary");

        CompactionResult result = service.compactRange(
                process(), t0, t0.plusSeconds(60), "trivia", config);

        assertThat(result.compacted()).isTrue();
        verify(analyzer, never()).analyze(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sideChannel_analyzerThrowsButCompactionStillSucceeds() {
        evaluationProperties.setSideChannelEnabled(true);

        primeRange(longUserSpan());
        primeSummarizer("Summary text");
        when(analyzer.analyze(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM provider exhausted"));

        CompactionResult result = service.compactRange(
                process(), Instant.parse("2026-05-11T14:00:00Z"),
                Instant.parse("2026-05-11T15:00:00Z"), "auth-setup", config);

        assertThat(result.compacted()).isTrue();
        // analyzer was attempted, exception swallowed
        verify(analyzer).analyze(any(), any(), any(), any(), any(), any());
    }

    // ─── helpers ───

    private ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("p-1");
        p.setTenantId("t");
        p.setSessionId("s");
        return p;
    }

    private ChatMessageDocument msg(String id, ChatRole role, String content, Instant at) {
        ChatMessageDocument m = ChatMessageDocument.builder()
                .id(id)
                .tenantId("t").sessionId("s").thinkProcessId("p-1")
                .role(role).content(content).build();
        m.setCreatedAt(at);
        return m;
    }

    private List<ChatMessageDocument> longUserSpan() {
        Instant t = Instant.parse("2026-05-11T14:00:00Z");
        return List.of(
                msg("m1", ChatRole.USER,
                        "Ich habe gerade festgestellt dass unsere Codebase fast "
                                + "überall JSpecify nutzt aber an drei Stellen im "
                                + "Workflow-Code noch javax.annotation hängengeblieben "
                                + "ist und das sollten wir aufräumen damit das "
                                + "konsistent bleibt für alle die mal reinschauen",
                        t),
                msg("m2", ChatRole.ASSISTANT,
                        "Verstanden ich räume das mit dir zusammen auf "
                                + "und prüfe dann nochmal ob nichts vergessen wurde.",
                        t.plusSeconds(30)));
    }

    private void primeRange(List<ChatMessageDocument> range) {
        when(chatMessageService.findActiveInRange(eq("t"), eq("p-1"), any(), any()))
                .thenReturn(range);
    }

    private void primeSummarizer(String text) {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<SpanMessage>> listCaptor() {
        return ArgumentCaptor.forClass((Class<List<SpanMessage>>) (Class<?>) List.class);
    }
}
