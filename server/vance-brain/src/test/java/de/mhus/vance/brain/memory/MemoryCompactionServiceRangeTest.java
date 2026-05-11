package de.mhus.vance.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ford.FordProperties;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Behaviour of the range-based recompaction path. Drives the path with
 * mocked dependencies and verifies: range selection via
 * {@code findActiveInRange}, summariser call, memory persistence
 * (kind=ARCHIVED_CHAT + range-specific metadata), archival of source
 * ids, SYSTEM-marker insertion (role + tag + chronology), idempotency
 * on empty range, summariser failure → noop.
 *
 * <p>See {@code planning/topic-recompaction.md} §3.
 */
class MemoryCompactionServiceRangeTest {

    private ChatMessageService chatMessageService;
    private MemoryService memoryService;
    private AiModelService aiModelService;
    private SessionService sessionService;
    private SettingService settingService;
    private FordProperties properties;
    private LlmCallTracker llmCallTracker;
    private ProgressEmitter progressEmitter;
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
        service = new MemoryCompactionService(
                chatMessageService, memoryService, aiModelService,
                sessionService, settingService, properties,
                llmCallTracker, progressEmitter);

        aiChat = mock(AiChat.class);
        chatModel = mock(ChatModel.class);
        when(aiChat.chatModel()).thenReturn(chatModel);
        when(aiModelService.createChat(eq(config), any(AiChatOptions.class)))
                .thenReturn(aiChat);
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
    }

    // ─── Helpers ────────────────────────────────────────────────────────

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

    private void primeSummarizer(String text) {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);
    }

    // ─── Behaviour ──────────────────────────────────────────────────────

    @Test
    void emptyRange_skipsLlmCallAndReturnsNoop() {
        when(chatMessageService.findActiveInRange(eq("t"), eq("p-1"), any(), any()))
                .thenReturn(List.of());

        CompactionResult result = service.compactRange(
                process(), Instant.parse("2026-05-11T14:00:00Z"),
                Instant.parse("2026-05-11T15:00:00Z"), "auth-setup", config);

        assertThat(result.compacted()).isFalse();
        assertThat(result.reason()).contains("empty range");
        verifyNoInteractions(memoryService, llmCallTracker);
    }

    @Test
    void summarizerFailure_skipsPersistenceAndReturnsNoop() {
        Instant t0 = Instant.parse("2026-05-11T14:00:00Z");
        when(chatMessageService.findActiveInRange(eq("t"), eq("p-1"), any(), any()))
                .thenReturn(List.of(msg("m1", ChatRole.USER, "hi", t0)));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        CompactionResult result = service.compactRange(
                process(), t0, t0.plusSeconds(1), "auth-setup", config);

        assertThat(result.compacted()).isFalse();
        assertThat(result.reason()).contains("summarizer failed").contains("boom");
        verifyNoInteractions(memoryService);
    }

    @Test
    void happyPath_persistsMemoryArchivesRangeAndInsertsSystemMarker() {
        Instant t1 = Instant.parse("2026-05-11T14:00:00Z");
        Instant t2 = Instant.parse("2026-05-11T14:05:00Z");
        Instant t3 = Instant.parse("2026-05-11T14:10:00Z");
        List<ChatMessageDocument> range = List.of(
                msg("m1", ChatRole.USER,      "Plan auth setup",  t1),
                msg("m2", ChatRole.ASSISTANT, "Sure — three steps", t2),
                msg("m3", ChatRole.ASSISTANT, "All three done",   t3));
        when(chatMessageService.findActiveInRange(eq("t"), eq("p-1"), eq(t1), eq(t3)))
                .thenReturn(range);
        primeSummarizer("Auth setup completed in three steps.");

        ArgumentCaptor<MemoryDocument> savedCap = ArgumentCaptor.forClass(MemoryDocument.class);
        when(memoryService.save(savedCap.capture())).thenAnswer(inv -> {
            MemoryDocument arg = inv.getArgument(0);
            arg.setId("mem-99");
            return arg;
        });
        when(chatMessageService.markArchived(any(), eq("mem-99"))).thenReturn(3L);

        CompactionResult result = service.compactRange(
                process(), t1, t3, "auth-setup", config);

        assertThat(result.compacted()).isTrue();
        assertThat(result.messagesCompacted()).isEqualTo(3);
        assertThat(result.memoryId()).isEqualTo("mem-99");

        // Memory payload — kind, content, sourceRefs, metadata recompaction flag.
        MemoryDocument saved = savedCap.getValue();
        assertThat(saved.getKind()).isEqualTo(MemoryKind.ARCHIVED_CHAT);
        assertThat(saved.getContent()).isEqualTo("Auth setup completed in three steps.");
        assertThat(saved.getSourceRefs()).containsExactly("m1", "m2", "m3");
        assertThat(saved.getMetadata())
                .containsEntry("recompaction", true)
                .containsEntry("topicLabel", "auth-setup")
                .containsEntry("compactedMessages", 3);

        // Archival was atomic on the captured ids.
        verify(chatMessageService).markArchived(List.of("m1", "m2", "m3"), "mem-99");

        // SYSTEM marker — role, content, tag, createdAt one ms after last range row.
        ArgumentCaptor<ChatMessageDocument> markerCap =
                ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService).append(markerCap.capture());
        ChatMessageDocument marker = markerCap.getValue();
        assertThat(marker.getRole()).isEqualTo(ChatRole.SYSTEM);
        assertThat(marker.getContent()).isEqualTo("Auth setup completed in three steps.");
        assertThat(marker.getTags()).containsExactly("RECOMPACTION:auth-setup");
        assertThat(marker.getCreatedAt()).isEqualTo(t3.plusMillis(1));
        assertThat(marker.getThinkProcessId()).isEqualTo("p-1");
    }

    @Test
    void blankSummary_returnsNoopAndDoesNotPersist() {
        Instant t0 = Instant.parse("2026-05-11T14:00:00Z");
        when(chatMessageService.findActiveInRange(eq("t"), eq("p-1"), any(), any()))
                .thenReturn(List.of(msg("m1", ChatRole.USER, "hi", t0)));
        primeSummarizer("   "); // whitespace → trimmed to empty

        CompactionResult result = service.compactRange(
                process(), t0, t0.plusSeconds(1), "auth", config);

        assertThat(result.compacted()).isFalse();
        assertThat(result.reason()).contains("empty");
        verifyNoInteractions(memoryService);
        verify(chatMessageService, times(0)).markArchived(any(), anyString());
        // tracker still records the call attempt; we don't pin its absence.
    }
}
