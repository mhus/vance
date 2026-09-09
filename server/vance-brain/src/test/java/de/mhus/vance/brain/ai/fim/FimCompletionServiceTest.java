package de.mhus.vance.brain.ai.fim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.OutputTokenParam;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link FimCompletionService}. Everything below the
 * chat interface is mocked — no real provider, no real catalog.
 */
class FimCompletionServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "_tenant";
    private static final String CALLER = "test-caller";
    private static final String SPEC = "lmstudio:qwen3-coder-30b";
    private static final String QWEN_TEMPLATE = "<fim_prefix>{prefix}<fim_suffix>{suffix}<fim_middle>";

    private SettingService settingService;
    private AiModelResolver aiModelResolver;
    private ModelCatalog modelCatalog;
    private AiModelService aiModelService;
    private AuditService auditService;
    private SimpleMeterRegistry registry;
    private FimCompletionService service;

    private AiChat aiChat;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        aiModelResolver = mock(AiModelResolver.class);
        modelCatalog = mock(ModelCatalog.class);
        aiModelService = mock(AiModelService.class);
        auditService = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        service = new FimCompletionService(
                settingService,
                aiModelResolver,
                modelCatalog,
                aiModelService,
                auditService,
                new MetricService(registry));

        aiChat = mock(AiChat.class);
        chatModel = mock(ChatModel.class);
        when(aiModelService.createChat(any(AiChatConfig.class), any(AiChatOptions.class), any(CallAttribution.class)))
                .thenReturn(aiChat);
        when(aiChat.chatModel()).thenReturn(chatModel);
    }

    // ── Configuration gate ─────────────────────────────────────────

    @Test
    void isConfigured_reflects_the_setting() {
        when(settingService.getStringValueCascade(TENANT, PROJECT, null, FimCompletionService.FIM_ALIAS_SETTING))
                .thenReturn(SPEC);
        assertThat(service.isConfigured(TENANT, PROJECT)).isTrue();

        when(settingService.getStringValueCascade(TENANT, PROJECT, null, FimCompletionService.FIM_ALIAS_SETTING))
                .thenReturn("  ");
        assertThat(service.isConfigured(TENANT, PROJECT)).isFalse();
    }

    @Test
    void completeMiddle_without_configuration_throws() {
        assertThatThrownBy(() -> service.completeMiddle(TENANT, PROJECT, CALLER, "a", "b"))
                .isInstanceOf(FimException.class)
                .hasMessageContaining(FimCompletionService.FIM_ALIAS_SETTING);
    }

    // ── Happy path ────────────────────────────────────────────────

    @Test
    void completeMiddle_renders_family_template_and_returns_the_middle() {
        configureFim();
        when(modelCatalog.lookupOrDefault(TENANT, PROJECT, "lmstudio", "lmstudio", "qwen3-coder-30b"))
                .thenReturn(modelInfo(QWEN_TEMPLATE));
        ChatResponse resp = response("filled middle");
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(resp);

        String middle = service.completeMiddle(TENANT, PROJECT, CALLER, "foo(", ")");

        assertThat(middle).isEqualTo("filled middle");

        // The prompt is the spliced template — nothing else, no system
        // message, no instruction wrapper.
        ArgumentCaptor<ChatRequest> req = ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(chatModel).chat(req.capture());
        List<ChatMessage> messages = req.getValue().messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(0)).singleText()).isEqualTo("<fim_prefix>foo(<fim_suffix>)<fim_middle>");

        // Sampling is fixed and conservative: low temperature, small cap,
        // FIM end markers as stop hints, short deadline.
        ArgumentCaptor<AiChatOptions> opts = ArgumentCaptor.forClass(AiChatOptions.class);
        org.mockito.Mockito.verify(aiModelService)
                .createChat(any(AiChatConfig.class), opts.capture(), any(CallAttribution.class));
        assertThat(opts.getValue().getTemperature()).isEqualTo(FimCompletionService.FIM_TEMPERATURE);
        assertThat(opts.getValue().getMaxTokens()).isEqualTo(FimCompletionService.FIM_MAX_TOKENS);
        assertThat(opts.getValue().getStopSequences())
                .containsExactlyElementsOf(FimCompletionService.FIM_STOP_SEQUENCES);

        assertThat(registry.counter("vance.fim.calls", "outcome", "success", "caller", CALLER)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void completeMiddle_counts_blank_middle_as_blank_outcome() {
        configureFim();
        when(modelCatalog.lookupOrDefault(TENANT, PROJECT, "lmstudio", "lmstudio", "qwen3-coder-30b"))
                .thenReturn(modelInfo(QWEN_TEMPLATE));
        // Model only emitted the end marker — nothing belongs in the hole.
        ChatResponse resp = response("```");
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(resp);

        assertThat(service.completeMiddle(TENANT, PROJECT, CALLER, "a", "b")).isEmpty();

        assertThat(registry.counter("vance.fim.calls", "outcome", "blank", "caller", CALLER)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void completeMiddle_missing_fim_template_fails_closed() {
        configureFim();
        when(modelCatalog.lookupOrDefault(TENANT, PROJECT, "lmstudio", "lmstudio", "qwen3-coder-30b"))
                .thenReturn(modelInfo(null));

        assertThatThrownBy(() -> service.completeMiddle(TENANT, PROJECT, CALLER, "a", "b"))
                .isInstanceOf(FimException.class)
                .hasMessageContaining("fimTemplate")
                .hasMessageContaining("qwen3-coder-30b");
        // No provider call was spent on a model that can't do FIM.
        org.mockito.Mockito.verify(aiModelService, org.mockito.Mockito.never())
                .createChat(any(AiChatConfig.class), any(AiChatOptions.class), any(CallAttribution.class));
    }

    @Test
    void completeMiddle_provider_failure_wraps_and_counts_as_error() {
        configureFim();
        when(modelCatalog.lookupOrDefault(TENANT, PROJECT, "lmstudio", "lmstudio", "qwen3-coder-30b"))
                .thenReturn(modelInfo(QWEN_TEMPLATE));
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.completeMiddle(TENANT, PROJECT, CALLER, "a", "b"))
                .isInstanceOf(FimException.class)
                .hasMessageContaining("connection refused");

        assertThat(registry.counter("vance.fim.calls", "outcome", "error", "caller", CALLER)
                        .count())
                .isEqualTo(1.0);
    }

    // ── Prompt rendering (static) ─────────────────────────────────

    @Test
    void renderFimPrompt_splices_both_markers() {
        assertThat(FimCompletionService.renderFimPrompt(QWEN_TEMPLATE, "before", "after"))
                .isEqualTo("<fim_prefix>before<fim_suffix>after<fim_middle>");
        assertThat(FimCompletionService.renderFimPrompt("[PREFIX]{prefix}[SUFFIX]{suffix}[MIDDLE]", "x", "y"))
                .isEqualTo("[PREFIX]x[SUFFIX]y[MIDDLE]");
    }

    @Test
    void renderFimPrompt_never_scans_user_content_for_markers() {
        // A template literal in the edited text must pass through
        // untouched — only the template itself is parsed.
        String prompt = FimCompletionService.renderFimPrompt(QWEN_TEMPLATE, "val = \"{suffix}\";", "rest");
        assertThat(prompt).isEqualTo("<fim_prefix>val = \"{suffix}\";<fim_suffix>rest<fim_middle>");
    }

    @Test
    void renderFimPrompt_rejects_malformed_template() {
        assertThatThrownBy(() -> FimCompletionService.renderFimPrompt("no markers", "a", "b"))
                .isInstanceOf(FimException.class);
        assertThatThrownBy(() -> FimCompletionService.renderFimPrompt("{suffix}{prefix}", "a", "b"))
                .isInstanceOf(FimException.class);
    }

    // ── Output trimming (static) ───────────────────────────────────

    @Test
    void trimMiddle_cuts_at_end_markers_and_strips() {
        assertThat(FimCompletionService.trimMiddle("middle```tail")).isEqualTo("middle");
        assertThat(FimCompletionService.trimMiddle("a<|fim_pad|>b")).isEqualTo("a");
        assertThat(FimCompletionService.trimMiddle("  padded  ")).isEqualTo("padded");
        assertThat(FimCompletionService.trimMiddle("")).isEmpty();
        assertThat(FimCompletionService.trimMiddle(null)).isEmpty();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private void configureFim() {
        when(settingService.getStringValueCascade(TENANT, PROJECT, null, FimCompletionService.FIM_ALIAS_SETTING))
                .thenReturn(SPEC);
        when(aiModelResolver.resolveOrDefault(SPEC, TENANT, PROJECT, null))
                .thenReturn(new AiModelResolver.Resolved("lmstudio", "lmstudio", "qwen3-coder-30b"));
    }

    private static ChatResponse response(String text) {
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.aiMessage()).thenReturn(dev.langchain4j.data.message.AiMessage.from(text));
        return resp;
    }

    private static ModelInfo modelInfo(String fimTemplate) {
        return new ModelInfo(
                "lmstudio",
                "qwen3-coder-30b",
                262_144,
                8192,
                ModelSize.LARGE,
                Set.of(ModelCapability.VISION),
                120,
                2,
                false,
                null,
                null,
                OutputTokenParam.MAX_TOKENS,
                Set.of(),
                null,
                null,
                false,
                fimTemplate);
    }
}
