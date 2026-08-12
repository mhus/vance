package de.mhus.vance.brain.frankie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.history.BufferingHistoryTagSink;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loop-level tests for {@link FrankieEngine}. Drives the engine with
 * a scripted {@link StreamingChatModel} so the four stop paths
 * (natural stop, tool-driven terminate, external interrupt, safety
 * nets) can be pinned down without a real LLM.
 */
class FrankieEngineSkeletonTest {

    private static final String PROC_ID = "proc-frankie-1";

    private ThinkProcessService thinkProcessService;
    private ChatMessageService chatMessageService;
    private EngineChatFactory engineChatFactory;
    private LlmCallTracker llmCallTracker;
    private ContextToolsApi tools;
    private BufferingHistoryTagSink tagSink;
    private ScriptedStreamingChatModel chatModel;
    private ObjectMapper objectMapper;
    private EnginePromptResolver enginePromptResolver;
    private SystemPromptComposer systemPromptComposer;
    private SkillResolver skillResolver;
    private SkillPromptComposer skillPromptComposer;
    private SessionService sessionService;

    private de.mhus.vance.brain.ai.attachment.AttachmentResolver attachmentResolver;
    private de.mhus.vance.brain.ai.ModelCatalog modelCatalog;
    private de.mhus.vance.brain.ai.attachment.ToolAttachmentSink attachmentSink;

    private FrankieEngine engine;
    private FrankieProperties properties;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        chatMessageService = mock(ChatMessageService.class);
        engineChatFactory = mock(EngineChatFactory.class);
        llmCallTracker = mock(LlmCallTracker.class);
        tools = mock(ContextToolsApi.class);
        tagSink = mock(BufferingHistoryTagSink.class);
        objectMapper = JsonMapper.builder().build();
        properties = new FrankieProperties();

        StreamingProperties streaming = new StreamingProperties();
        chatModel = new ScriptedStreamingChatModel();

        AiChat aiChat = mock(AiChat.class);
        lenient().when(aiChat.streamingChatModel()).thenReturn(chatModel);

        AiChatConfig cfg = new AiChatConfig("test", "scripted", "stub-key");
        ChatBehavior behavior = ChatBehavior.single(cfg);
        EngineChatFactory.EngineChatBundle bundle =
                new EngineChatFactory.EngineChatBundle(aiChat, behavior);
        lenient().when(engineChatFactory.forProcess(any(), any(), any())).thenReturn(bundle);
        // 4-arg overload: Frankie rebuilds the chat with the est-scaled
        // stream timeout after turn-start compaction.
        lenient().when(engineChatFactory.forProcess(any(), any(), any(), any()))
                .thenReturn(bundle);

        lenient().when(tools.primaryAsLc4j()).thenReturn(List.of());
        // Skills add no extra tools by default — the per-turn allow-set
        // stays untouched. `withAdditional(empty)` returns `this`.
        lenient().when(tools.withAdditional(any())).thenReturn(tools);

        enginePromptResolver = mock(EnginePromptResolver.class);
        systemPromptComposer = mock(SystemPromptComposer.class);
        lenient().when(enginePromptResolver.resolve(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(systemPromptComposer.compose(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));

        skillResolver = mock(SkillResolver.class);
        skillPromptComposer = mock(SkillPromptComposer.class);
        sessionService = mock(SessionService.class);
        lenient().when(skillPromptComposer.mergedTools(any()))
                .thenReturn(java.util.Set.of());
        lenient().when(skillPromptComposer.compose(any(), any())).thenReturn(null);
        lenient().when(sessionService.findBySessionId(any())).thenReturn(Optional.empty());

        de.mhus.vance.brain.memory.MemoryContextLoader memoryContextLoader =
                mock(de.mhus.vance.brain.memory.MemoryContextLoader.class);
        lenient().when(memoryContextLoader.composeBlock(any())).thenReturn(null);

        modelCatalog = mock(de.mhus.vance.brain.ai.ModelCatalog.class);
        de.mhus.vance.brain.ai.ModelInfo fakeModelInfo = new de.mhus.vance.brain.ai.ModelInfo(
                "test", "test-model",
                /*contextWindowTokens*/ 128_000,
                /*defaultMaxOutputTokens*/ 4096,
                de.mhus.vance.brain.ai.ModelSize.LARGE,
                java.util.Set.of(),
                /*timeoutSeconds*/ 60,
                /*actionLoopCorrections*/ 2,
                /*stripThinkTags*/ false,
                /*messageParser*/ null,
                /*pricing*/ null,
                de.mhus.vance.brain.ai.OutputTokenParam.MAX_TOKENS,
                java.util.Set.of(), null);
        lenient().when(modelCatalog.lookupOrDefault(
                        any(), any(), any(), any(), any()))
                .thenReturn(fakeModelInfo);
        de.mhus.vance.brain.memory.MemoryCompactionService memoryCompactionService =
                mock(de.mhus.vance.brain.memory.MemoryCompactionService.class);
        lenient().when(memoryCompactionService.compactIfNeeded(any(), any(), any(), any()))
                .thenReturn(de.mhus.vance.brain.memory.CompactionResult.noop("test"));

        attachmentResolver = mock(de.mhus.vance.brain.ai.attachment.AttachmentResolver.class);
        de.mhus.vance.brain.guard.CompletionGuardService completionGuard =
                mock(de.mhus.vance.brain.guard.CompletionGuardService.class);
        lenient().when(completionGuard.evaluate(any(), any(), anyBoolean()))
                .thenReturn(new de.mhus.vance.brain.guard.GuardEvaluation(false, null, null));
        engine = new FrankieEngine(
                thinkProcessService, properties, engineChatFactory,
                llmCallTracker, streaming, objectMapper,
                enginePromptResolver, systemPromptComposer,
                skillResolver, skillPromptComposer, sessionService,
                mock(de.mhus.vance.brain.context.PromptDateContextResolver.class),
                mock(de.mhus.vance.brain.prompt.ScratchpadPromptContributor.class),
                memoryContextLoader,
                modelCatalog, memoryCompactionService,
                new de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry(java.util.List.of()),
                completionGuard,
                new de.mhus.vance.brain.ai.attachment.AttachedUserMessageComposer(
                                attachmentResolver));

        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setTenantId("tenant-x");
        process.setSessionId("session-y");
        process.setProjectId("proj-1");
        process.setStatus(ThinkProcessStatus.RUNNING);
        process.setCreatedAt(Instant.now());

        ctx = mock(ThinkEngineContext.class);
        ClientEventPublisher events = mock(ClientEventPublisher.class);
        lenient().when(ctx.chatMessageService()).thenReturn(chatMessageService);
        lenient().when(ctx.tools()).thenReturn(tools);
        attachmentSink = new de.mhus.vance.brain.ai.attachment.ToolAttachmentSink();
        lenient().when(ctx.attachmentSink()).thenReturn(attachmentSink);
        lenient().when(ctx.drainPending()).thenReturn(List.of());
        lenient().when(ctx.historyTagSink()).thenReturn(tagSink);
        lenient().when(ctx.events()).thenReturn(events);
        lenient().when(chatMessageService.activeHistory(any(), any(), any())).thenReturn(List.of());
        lenient().when(chatMessageService.append(any())).thenAnswer(inv -> {
            ChatMessageDocument doc = inv.getArgument(0);
            doc.setId("msg-" + System.nanoTime());
            return doc;
        });
        // Default status read: same as snapshot.
        lenient().when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
    }

    // ─── Stop path 1: natural stop (always IDLE, both modes) ────────────

    @Test
    void naturalStop_emitsAssistantMessageAndStaysIdle() {
        chatModel.script(AiMessage.from("Done. Renamed two methods."));

        engine.runTurn(process, ctx);

        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.RUNNING);
        // Context stays alive — exits IDLE, not CLOSED.
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
        // Final assistant message persisted to chat log.
        verify(chatMessageService).append(any());
        // Reply emitted to parent / progress channel.
        verify(ctx).emitReply(eq("Done. Renamed two methods."), any(), any());
    }

    // ─── Stop path 1b: empty LLM response (model collapse) ──────────────

    @Test
    void emptyLlmResponse_persistsErrorMessageAndBlocks() {
        // Gemini-style collapse: no text, no tool calls, finish=STOP.
        // langchain4j AiMessage rejects null text — empty string
        // exercises the same engine branch (text().isBlank() == true,
        // hasToolExecutionRequests() == false).
        chatModel.script(AiMessage.from(""));

        engine.runTurn(process, ctx);

        // The standard natural-stop path would have silently dropped the turn
        // (no chat append, status IDLE). The empty-response branch instead:
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.BLOCKED);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
        // Assistant message persisted so the user sees the worker bailed.
        verify(chatMessageService).append(any());
        // Reply emitted so a parent (worker mode) or UI (session-primary)
        // sees the error too.
        verify(ctx).emitReply(org.mockito.ArgumentMatchers.contains("empty response"), any(), any());
    }

    @Test
    void emptyLlmResponseAtOutputCap_namesTheTokenLimitInsteadOfAGlitch() {
        // GLM/DeepSeek-style truncation: the reasoning pass consumed the
        // whole max_tokens budget, so the completion arrives empty with
        // finish=LENGTH. Deterministic — telling the user to "try again"
        // would send them in circles.
        chatModel.script(AiMessage.from(""));
        chatModel.finishReason(FinishReason.LENGTH);

        engine.runTurn(process, ctx);

        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.BLOCKED);
        verify(chatMessageService).append(any());
        // Message names the real cause and the actionable knob, and does
        // NOT claim a transient glitch.
        org.mockito.ArgumentCaptor<String> reply =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ctx).emitReply(reply.capture(), any(), any());
        assertThat(reply.getValue())
                .contains("output-token limit")
                .contains("maxTokens")
                .doesNotContain("transient");
    }

    // ─── Stop path 2: tool-driven terminate (mode-aware) ────────────────

    @Test
    void toolTerminate_workerMode_closesDoneAfterBatch() {
        process.setParentProcessId("parent-arthur-1");
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("task_complete")
                .arguments("{\"summary\":\"all done\"}")
                .build();
        chatModel.script(AiMessage.from("", List.of(call)));

        when(tools.invoke(eq("task_complete"), any()))
                .thenReturn(java.util.Map.of(
                        "summary", "all done",
                        FrankieTermination.RESULT_TERMINATE_KEY, true));

        engine.runTurn(process, ctx);

        verify(tools).invoke(eq("task_complete"), any());
        // Worker: explicit "done forever" → process is closed.
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    @Test
    void toolTerminate_sessionPrimaryMode_staysIdle() {
        // No parent → session-primary.
        process.setParentProcessId(null);
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("task_complete")
                .arguments("{\"summary\":\"all done\"}")
                .build();
        chatModel.script(AiMessage.from("", List.of(call)));

        when(tools.invoke(eq("task_complete"), any()))
                .thenReturn(java.util.Map.of(
                        "summary", "all done",
                        FrankieTermination.RESULT_TERMINATE_KEY, true));

        engine.runTurn(process, ctx);

        verify(tools).invoke(eq("task_complete"), any());
        // Session-primary: signal received but session stays open.
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
    }

    // ─── Stop path 3: external interrupt ────────────────────────────────

    @Test
    void externalInterrupt_suspendedBeforeFirstIteration_exitsWithoutLlmCall() {
        ThinkProcessDocument current = new ThinkProcessDocument();
        current.setId(PROC_ID);
        current.setStatus(ThinkProcessStatus.SUSPENDED);
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(current));

        engine.runTurn(process, ctx);

        // No LLM call should have happened — the ChatModel script is empty,
        // so any call would throw.
        assertThat(chatModel.callCount()).isEqualTo(0);
        // Status was set to RUNNING once at entry; the loop exits without a closeProcess.
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.RUNNING);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    @Test
    void haltRequested_breaksLoopAndParksPaused() {
        // Status stays RUNNING (the halt path sets only the out-of-band
        // flag, not the status) — the loop must still exit via the halt
        // check instead of grinding on into an LLM call.
        ThinkProcessDocument current = new ThinkProcessDocument();
        current.setId(PROC_ID);
        current.setStatus(ThinkProcessStatus.RUNNING);
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(current));
        when(thinkProcessService.isHaltRequested(PROC_ID)).thenReturn(true);

        engine.runTurn(process, ctx);

        assertThat(chatModel.callCount()).isEqualTo(0);
        verify(thinkProcessService).clearHalt(PROC_ID);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.PAUSED);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    // ─── Attachments (Phase 2) ──────────────────────────────────────

    @Test
    void attachedImage_ridesAlongAsAContentBlock() {
        // Frankie renders its prompt from the persisted chat log, which
        // stores text only — so an image attached to a coding session
        // used to be dropped between the inbox and the LLM call.
        de.mhus.vance.brain.ai.AiChatConfig visionCfg =
                new AiChatConfig("openai", "gpt-x", "stub-key");
        AiChat aiChat = mock(AiChat.class);
        lenient().when(aiChat.streamingChatModel()).thenReturn(chatModel);
        EngineChatFactory.EngineChatBundle visionBundle =
                new EngineChatFactory.EngineChatBundle(aiChat, ChatBehavior.single(visionCfg));
        lenient().when(engineChatFactory.forProcess(any(), any(), any())).thenReturn(visionBundle);
        lenient().when(engineChatFactory.forProcess(any(), any(), any(), any()))
                .thenReturn(visionBundle);
        lenient().when(modelCatalog.lookupOrDefault(any(), any(), any(), any(), any()))
                .thenReturn(visionModelInfo());
        when(attachmentResolver.resolveAll(any(), any(), any()))
                .thenReturn(List.of(new de.mhus.vance.brain.ai.attachment.ResolvedAttachment(
                        "doc-1", "image/png", new byte[] {1, 2, 3}, "screenshot.png")));
        when(ctx.drainPending()).thenReturn(List.of(userInputWithAttachment("look at this")));
        // Production shape: the text was already persisted, so the same
        // turn appears both in history and in the inbox. The rebuild has
        // to replace the history entry, not append next to it.
        when(chatMessageService.activeHistory(any(), any(), any()))
                .thenReturn(List.of(ChatMessageDocument.builder()
                        .role(de.mhus.vance.api.chat.ChatRole.USER)
                        .content("look at this")
                        .build()));
        chatModel.script(AiMessage.from("I see a screenshot."));

        engine.runTurn(process, ctx);

        dev.langchain4j.data.message.UserMessage userMessage =
                lastUserMessage(chatModel.requests().get(0));
        assertThat(userMessage.contents())
                .as("image block plus the user's text")
                .hasSize(2);
        assertThat(userMessage.contents().get(0).type())
                .isEqualTo(dev.langchain4j.data.message.ContentType.IMAGE);
        assertThat(countUserMessages(chatModel.requests().get(0)))
                .as("the rebuilt message replaces the history entry instead of doubling it")
                .isEqualTo(1);
    }

    @Test
    void turnWithoutAttachments_rendersFromHistoryUnchanged() {
        // The attachment path must not disturb the ordinary turn: no
        // resolver call, no rebuild, history as before.
        when(ctx.drainPending()).thenReturn(List.of(userInput("plain question")));
        chatModel.script(AiMessage.from("answer"));

        engine.runTurn(process, ctx);

        verify(attachmentResolver, never()).resolveAll(any(), any(), any());
    }

    private static de.mhus.vance.brain.ai.ModelInfo visionModelInfo() {
        return new de.mhus.vance.brain.ai.ModelInfo(
                "openai", "gpt-x", 128_000, 4096,
                de.mhus.vance.brain.ai.ModelSize.LARGE,
                java.util.Set.of(de.mhus.vance.brain.ai.ModelCapability.VISION),
                60, 2, false, null, null,
                de.mhus.vance.brain.ai.OutputTokenParam.MAX_TOKENS,
                java.util.Set.of(), null);
    }

    private static de.mhus.vance.brain.thinkengine.SteerMessage.UserChatInput userInput(
            String text) {
        return new de.mhus.vance.brain.thinkengine.SteerMessage.UserChatInput(
                Instant.now(), null, "wile.coyote", null, text,
                List.of(), false, null, null, null);
    }

    private static de.mhus.vance.brain.thinkengine.SteerMessage.UserChatInput
            userInputWithAttachment(String text) {
        return new de.mhus.vance.brain.thinkengine.SteerMessage.UserChatInput(
                Instant.now(), null, "wile.coyote", null, text,
                List.of(new de.mhus.vance.api.attachment.AttachmentRef("doc-1")),
                false, null, null, null);
    }

    private static int countUserMessages(ChatRequest request) {
        int n = 0;
        for (dev.langchain4j.data.message.ChatMessage m : request.messages()) {
            if (m instanceof dev.langchain4j.data.message.UserMessage) n++;
        }
        return n;
    }

    /** The last user-role message of a request — the one this turn built. */
    private static dev.langchain4j.data.message.UserMessage lastUserMessage(ChatRequest request) {
        dev.langchain4j.data.message.UserMessage last = null;
        for (dev.langchain4j.data.message.ChatMessage m : request.messages()) {
            if (m instanceof dev.langchain4j.data.message.UserMessage um) last = um;
        }
        assertThat(last).as("request carries a user message").isNotNull();
        return last;
    }

    @Test
    void imageFromAToolCall_isShownToTheModelOnTheNextIteration() {
        // A screenshot arrives as an image content block, gets harvested
        // into a document, and can only reach the model on a message of
        // its own — a tool result is text in the OpenAI-compatible API.
        de.mhus.vance.brain.ai.AiChatConfig visionCfg =
                new AiChatConfig("openai", "gpt-x", "stub-key");
        AiChat aiChat = mock(AiChat.class);
        lenient().when(aiChat.streamingChatModel()).thenReturn(chatModel);
        EngineChatFactory.EngineChatBundle visionBundle =
                new EngineChatFactory.EngineChatBundle(aiChat, ChatBehavior.single(visionCfg));
        lenient().when(engineChatFactory.forProcess(any(), any(), any())).thenReturn(visionBundle);
        lenient().when(engineChatFactory.forProcess(any(), any(), any(), any()))
                .thenReturn(visionBundle);
        lenient().when(modelCatalog.lookupOrDefault(any(), any(), any(), any(), any()))
                .thenReturn(visionModelInfo());
        when(attachmentResolver.resolveAll(any(), any(), any()))
                .thenReturn(List.of(new de.mhus.vance.brain.ai.attachment.ResolvedAttachment(
                        "doc-1", "image/png", new byte[] {1, 2, 3}, "screenshot.png")));

        ToolExecutionRequest shot = ToolExecutionRequest.builder()
                .id("call-1").name("chrome__take_screenshot").arguments("{}").build();
        chatModel.script(AiMessage.from("", List.of(shot)));
        chatModel.script(AiMessage.from("I can see the page."));
        // The harvester runs inside the dispatch path; here the tool
        // invocation stands in for it by filling the sink.
        when(tools.invoke(eq("chrome__take_screenshot"), any())).thenAnswer(inv -> {
            attachmentSink.emit(List.of(
                    new de.mhus.vance.api.attachment.AttachmentRef("doc-1")));
            return java.util.Map.of("content", List.of(
                    java.util.Map.of("type", "image", "path", "_chatbox/shot.png")));
        });

        engine.runTurn(process, ctx);

        dev.langchain4j.data.message.UserMessage shown =
                lastUserMessage(chatModel.requests().get(1));
        assertThat(shown.contents().get(0).type())
                .as("the screenshot reaches the second iteration as an image block")
                .isEqualTo(dev.langchain4j.data.message.ContentType.IMAGE);
    }

    @Test
    void toolImage_isAddedOnce_notPerIteration() {
        // The message stays in the conversation for the rest of the turn
        // — that is what a message is. What draining guarantees is that
        // it is *appended* once: without it every later iteration would
        // add the same picture again and the context would fill up.
        de.mhus.vance.brain.ai.AiChatConfig visionCfg =
                new AiChatConfig("openai", "gpt-x", "stub-key");
        AiChat aiChat = mock(AiChat.class);
        lenient().when(aiChat.streamingChatModel()).thenReturn(chatModel);
        EngineChatFactory.EngineChatBundle visionBundle =
                new EngineChatFactory.EngineChatBundle(aiChat, ChatBehavior.single(visionCfg));
        lenient().when(engineChatFactory.forProcess(any(), any(), any())).thenReturn(visionBundle);
        lenient().when(engineChatFactory.forProcess(any(), any(), any(), any()))
                .thenReturn(visionBundle);
        lenient().when(modelCatalog.lookupOrDefault(any(), any(), any(), any(), any()))
                .thenReturn(visionModelInfo());
        when(attachmentResolver.resolveAll(any(), any(), any()))
                .thenReturn(List.of(new de.mhus.vance.brain.ai.attachment.ResolvedAttachment(
                        "doc-1", "image/png", new byte[] {1, 2, 3}, "screenshot.png")));

        ToolExecutionRequest shot = ToolExecutionRequest.builder()
                .id("call-1").name("chrome__take_screenshot").arguments("{}").build();
        ToolExecutionRequest other = ToolExecutionRequest.builder()
                .id("call-2").name("noop_tool").arguments("{}").build();
        chatModel.script(AiMessage.from("", List.of(shot)));
        chatModel.script(AiMessage.from("", List.of(other)));
        chatModel.script(AiMessage.from("done"));
        when(tools.invoke(eq("chrome__take_screenshot"), any())).thenAnswer(inv -> {
            attachmentSink.emit(List.of(
                    new de.mhus.vance.api.attachment.AttachmentRef("doc-1")));
            return java.util.Map.of("ok", true);
        });
        when(tools.invoke(eq("noop_tool"), any())).thenReturn(java.util.Map.of("ok", true));

        engine.runTurn(process, ctx);

        assertThat(countImageBlocks(chatModel.requests().get(2)))
                .as("the picture is in the conversation exactly once, not once per iteration")
                .isEqualTo(1);
    }

    private static int countImageBlocks(ChatRequest request) {
        int n = 0;
        for (dev.langchain4j.data.message.ChatMessage m : request.messages()) {
            if (m instanceof dev.langchain4j.data.message.UserMessage um) {
                for (dev.langchain4j.data.message.Content c : um.contents()) {
                    if (c.type() == dev.langchain4j.data.message.ContentType.IMAGE) n++;
                }
            }
        }
        return n;
    }

    // ─── Deferred-tool activation inside the turn ───────────────────

    @Test
    void deferredToolActivatedMidTurn_reachesTheNextIterationsToolSpecs() {
        // Field case: the model called tool_description('chrome__new_page'),
        // was told activated:true, and then could not call the tool —
        // Frankie built its tool specs once before the loop, so the
        // activation stayed invisible until the next turn. It re-described
        // four more times and told the user the tools were unavailable.
        ToolSpecification base = ToolSpecification.builder().name("tool_description").build();
        ToolSpecification activated = ToolSpecification.builder().name("chrome__new_page").build();
        when(tools.primaryAsLc4j())
                .thenReturn(List.of(base))          // turn start
                .thenReturn(List.of(base, activated));  // after the batch

        ToolExecutionRequest describe = ToolExecutionRequest.builder()
                .id("call-1")
                .name("tool_description")
                .arguments("{\"names\":[\"chrome__new_page\"]}")
                .build();
        chatModel.script(AiMessage.from("", List.of(describe)));
        chatModel.script(AiMessage.from("Opened the page."));
        when(tools.invoke(eq("tool_description"), any()))
                .thenReturn(java.util.Map.of("activated", true));

        engine.runTurn(process, ctx);

        assertThat(chatModel.requests()).hasSize(2);
        assertThat(chatModel.requests().get(0).toolSpecifications())
                .extracting(ToolSpecification::name)
                .containsExactly("tool_description");
        assertThat(chatModel.requests().get(1).toolSpecifications())
                .as("the tool activated in iteration 1 must be callable in iteration 2")
                .extracting(ToolSpecification::name)
                .contains("chrome__new_page");
    }

    @Test
    void toolViewIsRebuiltFromTheContext_notFromTheTurnStartSnapshot() {
        // The rebuild has to go back through ctx.tools(), because that is
        // what re-reads activatedDeferredTools from Mongo; reusing the
        // snapshot would keep returning the pre-activation view.
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("noop_tool")
                .arguments("{}")
                .build();
        chatModel.script(AiMessage.from("", List.of(call)));
        chatModel.script(AiMessage.from("done"));
        when(tools.invoke(eq("noop_tool"), any())).thenReturn(java.util.Map.of("ok", true));

        engine.runTurn(process, ctx);

        // Once at turn start, once after the tool batch.
        verify(ctx, org.mockito.Mockito.atLeast(2)).tools();
    }

    @Test
    void externalInterrupt_closedMidLoop_exitsAfterStatusChange() {
        // First iter: LLM asks for a tool call. Before second iter, status flips to CLOSED.
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("noop_tool")
                .arguments("{}")
                .build();
        chatModel.script(AiMessage.from("", List.of(call)));

        when(tools.invoke(eq("noop_tool"), any())).thenAnswer(inv -> {
            // After this tool runs, flip the persisted status to CLOSED.
            ThinkProcessDocument closed = new ThinkProcessDocument();
            closed.setId(PROC_ID);
            closed.setStatus(ThinkProcessStatus.CLOSED);
            when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(closed));
            return java.util.Map.of("ok", true);
        });

        engine.runTurn(process, ctx);

        assertThat(chatModel.callCount()).isEqualTo(1);
        // No closeProcess call from the engine — the process was already closed externally.
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    // ─── Stop path 4a: wallclock safety net ─────────────────────────────

    @Test
    void wallclockExceeded_setsBlocked() {
        properties.setMaxWallclockMinutes(0);  // anything > 0 ms past createdAt trips it
        process.setCreatedAt(Instant.now().minusSeconds(60));

        engine.runTurn(process, ctx);

        assertThat(chatModel.callCount()).isEqualTo(0);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.BLOCKED);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    // ─── Stop path 4b: idle-stuck safety net ────────────────────────────

    @Test
    void idleStuck_sameToolRepeated_setsBlocked() {
        properties.setIdleStuckThreshold(2);
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-X")
                .name("stuck_tool")
                .arguments("{\"path\":\"X\"}")
                .build();
        // Scripted to always return the same tool call.
        chatModel.scriptRepeating(AiMessage.from("", List.of(call)));
        when(tools.invoke(eq("stuck_tool"), any())).thenReturn(java.util.Map.of("ok", true));

        engine.runTurn(process, ctx);

        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.BLOCKED);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    @Test
    void idleStuck_pollingBatchesExempt_notBlocked() {
        // Repeatedly polling exec_status while a long job runs is legitimate
        // waiting, not a stuck loop — it must NOT trip the idle-stuck net.
        properties.setIdleStuckThreshold(2);
        properties.setPollThrottleStepMs(0); // no real sleeps in the unit test
        ToolExecutionRequest poll = ToolExecutionRequest.builder()
                .id("call-P")
                .name("exec_status")
                .arguments("{\"id\":\"job-1\"}")
                .build();
        // 3 identical polls (> threshold) then a natural stop.
        chatModel.script(AiMessage.from("", List.of(poll)));
        chatModel.script(AiMessage.from("", List.of(poll)));
        chatModel.script(AiMessage.from("", List.of(poll)));
        chatModel.script(AiMessage.from("Build still running — will keep polling."));
        when(tools.invoke(eq("exec_status"), any()))
                .thenReturn(java.util.Map.of("id", "job-1", "status", "RUNNING"));

        engine.runTurn(process, ctx);

        verify(thinkProcessService, never())
                .updateStatus(PROC_ID, ThinkProcessStatus.BLOCKED);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
    }

    // ─── Metadata + lifecycle smoke ─────────────────────────────────────

    @Test
    void metadata_returnsExpectedValues() {
        assertThat(engine.name()).isEqualTo("frankie");
        assertThat(engine.title()).contains("Frankie");
        assertThat(engine.version()).isEqualTo("0.5.0");
        assertThat(engine.description()).isNotBlank();
    }

    @Test
    void stop_closesProcessWithStoppedReason() {
        engine.stop(process, ctx);
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.STOPPED);
    }

    @Test
    void terminationConventionKey_isStable() {
        assertThat(FrankieTermination.RESULT_TERMINATE_KEY).isEqualTo("_terminate");
    }

    @Test
    void allowedTools_engineBaselineSetExposed() {
        // Frankie returns a non-empty engine-default set so the resolver
        // can compute (engineDefault ∪ recipe.add) ∖ recipe.remove instead
        // of falling through to "no engine-level restriction" (which would
        // dump the full tenant tool buffet into every LLM call).
        var set = engine.allowedTools();
        assertThat(set).isNotEmpty();
        // Discovery + intro essentials
        assertThat(set).contains("tool_list", "tool_description", "how_do_i",
                "manual_read", "tool_result_read");
        // Sub-worker spawn — Frankie's escape hatch
        assertThat(set).contains("process_spawn");
        // User-facing signal
        assertThat(set).contains("vance_notify");
        // Generic work-target file / exec wrappers + work_target_get/set
        assertThat(set).contains("file_read", "file_write", "file_edit",
                "file_list", "file_find", "file_grep", "file_head_tail",
                "file_count", "exec_run", "exec_status", "exec_tail",
                "exec_kill", "work_target_get", "work_target_set");
        // Plan-tracking CRUD trio (reduced Plan-Mode variant, §9)
        assertThat(set).contains("todo_create", "todo_update", "todo_remove");
    }

    // ──────────────────── ScriptedStreamingChatModel ─────────────────────

    /**
     * Minimal stub streaming model — delivers a pre-scripted
     * {@link AiMessage} synchronously via the handler's
     * {@code onCompleteResponse}. Either a single-shot script (consumed
     * on first call, exhausts after) or a repeating one (every call
     * gets the same answer — used for idle-stuck testing).
     */
    private static class ScriptedStreamingChatModel implements StreamingChatModel {
        private final Deque<AiMessage> queue = new ArrayDeque<>();
        private final List<ChatRequest> requests = new java.util.ArrayList<>();
        private @org.jspecify.annotations.Nullable AiMessage repeating;
        private @org.jspecify.annotations.Nullable FinishReason finishReason;
        private int calls;

        void script(AiMessage msg) {
            queue.add(msg);
        }

        /** Finish reason stamped on every scripted completion. */
        void finishReason(FinishReason reason) {
            this.finishReason = reason;
        }

        void scriptRepeating(AiMessage msg) {
            this.repeating = msg;
        }

        int callCount() {
            return calls;
        }

        /** Requests as the engine issued them — one per loop iteration. */
        List<ChatRequest> requests() {
            return List.copyOf(requests);
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            calls++;
            requests.add(request);
            AiMessage msg;
            if (repeating != null) {
                msg = repeating;
            } else if (!queue.isEmpty()) {
                msg = queue.poll();
            } else {
                handler.onError(new IllegalStateException(
                        "ScriptedStreamingChatModel: no more scripted responses"));
                return;
            }
            ChatResponse.Builder builder = ChatResponse.builder().aiMessage(msg);
            if (finishReason != null) {
                builder.finishReason(finishReason);
            }
            ChatResponse response = builder.build();
            String text = msg.text();
            if (text != null && !text.isEmpty()) {
                handler.onPartialResponse(text);
            }
            handler.onCompleteResponse(response);
        }
    }
}
