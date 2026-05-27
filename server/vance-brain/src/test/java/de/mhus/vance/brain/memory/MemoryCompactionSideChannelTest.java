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
import de.mhus.vance.brain.prak.PrakSideChannelRunner;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryService;
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
 * Compaction → side-channel <em>delegation</em> contract. The actual
 * analyzer / sanitizer / strength / promotion / audit pipeline lives
 * in {@link PrakSideChannelRunner} now; this test only verifies that
 * {@code MemoryCompactionService} calls the runner with the right
 * span + projectId + trigger label after a successful compaction.
 * Pipeline behavior is covered by {@code PrakSideChannelRunnerTest}.
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
    private PrakSideChannelRunner runner;
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
        runner = mock(PrakSideChannelRunner.class);

        de.mhus.vance.brain.prak.PrakProperties prakProps =
                new de.mhus.vance.brain.prak.PrakProperties();
        service = new MemoryCompactionService(
                chatMessageService, memoryService, aiModelService,
                sessionService, settingService, properties,
                llmCallTracker, progressEmitter, metricService, runner,
                new de.mhus.vance.brain.memory.StrengthAwareSelector(prakProps),
                new de.mhus.vance.brain.memory.CompactionTriggerService(prakProps),
                prakProps,
                mock(de.mhus.vance.brain.prak.PrakPeriodicTrigger.class));

        aiChat = mock(AiChat.class);
        chatModel = mock(ChatModel.class);
        when(aiChat.chatModel()).thenReturn(chatModel);
        when(aiModelService.createChat(eq(config), any(AiChatOptions.class)))
                .thenReturn(aiChat);
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());

        when(memoryService.save(any())).thenAnswer(inv -> {
            MemoryDocument arg = inv.getArgument(0);
            arg.setId("mem-id");
            return arg;
        });
        when(chatMessageService.markArchived(any(), anyString())).thenReturn(0L);
    }

    @Test
    void compactRange_invokesRunnerWithRangeAndLabel() {
        primeRange(longUserSpan());
        primeSummarizer("Summary text");

        CompactionResult result = service.compactRange(
                process(), Instant.parse("2026-05-11T14:00:00Z"),
                Instant.parse("2026-05-11T15:00:00Z"), "auth-setup", config);

        assertThat(result.compacted()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessageDocument>> spanCap =
                ArgumentCaptor.forClass((Class<List<ChatMessageDocument>>) (Class<?>) List.class);
        ArgumentCaptor<String> labelCap = ArgumentCaptor.forClass(String.class);
        verify(runner).run(any(), any(), spanCap.capture(), labelCap.capture());
        // The trigger label carries the compaction mode + topic so audit can group.
        assertThat(labelCap.getValue()).contains("auth-setup");
        assertThat(spanCap.getValue()).hasSize(longUserSpan().size());
    }

    @Test
    void compactRange_runnerExceptionDoesNotFailCompaction() {
        primeRange(longUserSpan());
        primeSummarizer("Summary text");
        // Defensive — even if the runner itself throws (the runner is
        // supposed to swallow internally, but we belt-and-suspenders),
        // compaction must still succeed.
        org.mockito.Mockito.doThrow(new RuntimeException("runner blew up"))
                .when(runner).run(any(), any(), any(), any());

        CompactionResult result = service.compactRange(
                process(), Instant.parse("2026-05-11T14:00:00Z"),
                Instant.parse("2026-05-11T15:00:00Z"), "auth-setup", config);

        // compaction itself succeeded; side-channel failure is logged.
        assertThat(result.compacted()).isTrue();
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
                msg("m1", ChatRole.USER, "Substantial user message about something important", t),
                msg("m2", ChatRole.ASSISTANT, "Assistant reply", t.plusSeconds(30)));
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
}
