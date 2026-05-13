package de.mhus.vance.brain.jeltz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link JeltzEngine}. Drives the engine with a scripted
 * {@link ChatModel} so the validator-loop, retry behaviour, and result
 * wrapper writes can be pinned down without a real LLM.
 */
class JeltzEngineTest {

    private static final String PROC_ID = "proc-jeltz-1";

    private ThinkProcessService thinkProcessService;
    private ChatMessageService chatMessageService;
    private EngineChatFactory engineChatFactory;
    private EnginePromptResolver enginePromptResolver;
    private ScriptedChatModel chatModel;
    private ObjectMapper objectMapper;

    private JeltzEngine engine;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        chatMessageService = mock(ChatMessageService.class);
        engineChatFactory = mock(EngineChatFactory.class);
        enginePromptResolver = mock(EnginePromptResolver.class);
        objectMapper = JsonMapper.builder().build();

        chatModel = new ScriptedChatModel();
        AiChat aiChat = mock(AiChat.class);
        lenient().when(aiChat.chatModel()).thenReturn(chatModel);

        AiChatConfig cfg = new AiChatConfig("test", "scripted", "stub-key");
        ChatBehavior behavior = ChatBehavior.single(cfg);
        EngineChatFactory.EngineChatBundle bundle =
                new EngineChatFactory.EngineChatBundle(aiChat, behavior);
        lenient().when(engineChatFactory.forProcess(any(), any(), any()))
                .thenReturn(bundle);

        lenient().when(enginePromptResolver.resolve(any(), anyString(), anyString()))
                .thenReturn("ENGINE_PROMPT");

        engine = new JeltzEngine(
                thinkProcessService, objectMapper,
                engineChatFactory, enginePromptResolver);

        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        process.setProjectId("proj-1");
        process.setStatus(ThinkProcessStatus.INIT);
        process.setEngineParams(new LinkedHashMap<>());

        ctx = mock(ThinkEngineContext.class);
        lenient().when(ctx.chatMessageService()).thenReturn(chatMessageService);
        lenient().when(chatMessageService.append(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── Happy path ─────────────────────────────────────────────────────

    @Test
    void start_validParamsAndCleanReply_writesSuccessWrapperAndClosesDone() throws Exception {
        Map<String, Object> schema = simpleSchema();
        process.getEngineParams().put("prompt", "list two colors");
        process.getEngineParams().put("schema", schema);

        chatModel.script(List.of("{\"name\":\"red\",\"count\":2}"));

        engine.start(process, ctx);

        Map<String, Object> wrapper = captureAssistantWrapper();
        assertThat(wrapper.get("success")).isEqualTo(true);
        assertThat(wrapper.get("attempts")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
        assertThat(data).containsEntry("name", "red").containsEntry("count", 2);
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    @Test
    void start_invalidThenValidReply_retriesAndReportsAttemptCount() throws Exception {
        process.getEngineParams().put("prompt", "answer me");
        process.getEngineParams().put("schema", simpleSchema());
        // attempt 1: missing required 'name' → violation
        // attempt 2: valid object
        chatModel.script(List.of(
                "{\"count\":1}",
                "{\"name\":\"blue\",\"count\":1}"));

        engine.start(process, ctx);

        Map<String, Object> wrapper = captureAssistantWrapper();
        assertThat(wrapper.get("success")).isEqualTo(true);
        assertThat(wrapper.get("attempts")).isEqualTo(2);
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    @Test
    void start_alwaysInvalid_writesMaxAttemptsExceededWithLastInvalid() throws Exception {
        process.getEngineParams().put("prompt", "p");
        process.getEngineParams().put("schema", simpleSchema());
        process.getEngineParams().put("maxAttempts", 2);
        chatModel.script(List.of(
                "{\"count\":1}",
                "{\"count\":2}"));

        engine.start(process, ctx);

        Map<String, Object> wrapper = captureAssistantWrapper();
        assertThat(wrapper.get("success")).isEqualTo(false);
        assertThat(wrapper.get("attempts")).isEqualTo(2);
        assertThat(wrapper.get("error")).isEqualTo("max_attempts_exceeded");
        assertThat(String.valueOf(wrapper.get("message")))
                .contains("Schema not satisfied");
        assertThat(wrapper.get("lastInvalid")).isNotNull();
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    // ─── Param-validation paths (no LLM call should happen) ─────────────

    @Test
    void start_missingPrompt_writesInvalidParamsAndSkipsLlm() throws Exception {
        process.getEngineParams().put("schema", simpleSchema());

        engine.start(process, ctx);

        Map<String, Object> wrapper = captureAssistantWrapper();
        assertThat(wrapper.get("success")).isEqualTo(false);
        assertThat(wrapper.get("error")).isEqualTo("invalid_params");
        assertThat(wrapper.get("attempts")).isEqualTo(0);
        // ChatModel was never asked — script is still empty so a chat()
        // call would throw IllegalStateException.
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
        verify(engineChatFactory, never()).forProcess(any(), any(), any());
    }

    @Test
    void start_missingSchema_writesInvalidParams() throws Exception {
        process.getEngineParams().put("prompt", "p");

        engine.start(process, ctx);

        Map<String, Object> wrapper = captureAssistantWrapper();
        assertThat(wrapper.get("error")).isEqualTo("invalid_params");
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    @Test
    void start_schemaWithoutObjectType_writesInvalidSchema() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        process.getEngineParams().put("prompt", "p");
        process.getEngineParams().put("schema", schema);

        engine.start(process, ctx);

        Map<String, Object> wrapper = captureAssistantWrapper();
        assertThat(wrapper.get("error")).isEqualTo("invalid_schema");
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    // ─── LLM error handling ────────────────────────────────────────────

    @Test
    void start_llmThrows_writesLlmErrorWrapper() throws Exception {
        process.getEngineParams().put("prompt", "p");
        process.getEngineParams().put("schema", simpleSchema());
        chatModel.throwOnNextCall(new RuntimeException("provider down"));

        engine.start(process, ctx);

        Map<String, Object> wrapper = captureAssistantWrapper();
        assertThat(wrapper.get("success")).isEqualTo(false);
        assertThat(wrapper.get("error")).isEqualTo("llm_error");
        assertThat(String.valueOf(wrapper.get("message"))).contains("provider down");
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    // ─── stop() path ───────────────────────────────────────────────────

    @Test
    void stop_beforeClosed_writesStoppedAndClosesStopped() throws Exception {
        process.setStatus(ThinkProcessStatus.RUNNING);

        engine.stop(process, ctx);

        Map<String, Object> wrapper = captureAssistantWrapper();
        assertThat(wrapper.get("error")).isEqualTo("stopped");
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.STOPPED);
    }

    @Test
    void stop_alreadyClosed_isNoOp() {
        process.setStatus(ThinkProcessStatus.CLOSED);

        engine.stop(process, ctx);

        verify(chatMessageService, never()).append(any());
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private Map<String, Object> simpleSchema() {
        Map<String, Object> name = Map.of("type", "string");
        Map<String, Object> count = Map.of("type", "integer");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("count", count);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("name"));
        schema.put("properties", properties);
        return schema;
    }

    /**
     * Pulls the assistant-role chat-message that the engine emits as the
     * result wrapper, deserialises its JSON content, and returns it for
     * assertions. There may be a prior user-role append (the synthesised
     * prompt) when params were valid enough to reach the loop.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> captureAssistantWrapper() throws Exception {
        ArgumentCaptor<ChatMessageDocument> captor =
                ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService, org.mockito.Mockito.atLeastOnce()).append(captor.capture());
        ChatMessageDocument assistant = captor.getAllValues().stream()
                .filter(m -> m.getRole() == ChatRole.ASSISTANT)
                .reduce((a, b) -> b)  // last assistant message
                .orElseThrow(() -> new AssertionError(
                        "no assistant-role chat message was appended"));
        return objectMapper.readValue(assistant.getContent(), Map.class);
    }

    // ──────────────────── ScriptedChatModel ──────────────────────────

    /** Minimal stub returning queued responses; throws once on demand. */
    private static class ScriptedChatModel implements ChatModel {
        private final Deque<String> responses = new ArrayDeque<>();
        private RuntimeException nextError;

        void script(List<String> entries) {
            responses.clear();
            responses.addAll(entries);
        }

        void throwOnNextCall(RuntimeException error) {
            this.nextError = error;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            if (nextError != null) {
                RuntimeException toThrow = nextError;
                nextError = null;
                throw toThrow;
            }
            if (responses.isEmpty()) {
                throw new IllegalStateException(
                        "ScriptedChatModel: no more scripted responses");
            }
            String text = responses.pop();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }
}
