package de.mhus.vance.brain.ai.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.SystemBlockKind;
import de.mhus.vance.brain.ai.ThinkingLevel;
import de.mhus.vance.brain.ai.VanceSystemMessage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cache-marker placement rules in {@link AnthropicRequestMapper}.
 *
 * <p>Tests target {@link AnthropicRequestMapper#buildBody} directly so
 * they don't need an {@code AnthropicClient} or live SDK builder — the
 * generated body Map is exactly what eventually flows out via
 * {@code MessageCreateParams.Builder.putAdditionalBodyProperty(...)}.
 */
class AnthropicRequestMapperTest {

    @Test
    void cacheMarker_setsOnLastSystemBlock_whenAllStatic() {
        ChatRequest request = buildRequest(List.of(
                SystemMessage.from("rule one"),
                SystemMessage.from("rule two"),
                UserMessage.from("hi")));
        AiChatOptions options = optionsWithBoundary(CacheBoundary.SYSTEM_AND_TOOLS);

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);
        List<Map<String, Object>> system = systemBlocks(body);

        assertThat(system).hasSize(2);
        assertThat(system.get(0)).doesNotContainKey("cache_control");
        assertThat(system.get(1)).containsKey("cache_control");
        assertThat(system.get(1).get("cache_control"))
                .isEqualTo(Map.of("type", "ephemeral"));
    }

    @Test
    void cacheMarker_setsOnLastStaticBlock_whenDynamicBlocksFollow() {
        ChatRequest request = buildRequest(List.of(
                SystemMessage.from("static base"),
                new VanceSystemMessage("static skills", SystemBlockKind.STATIC),
                VanceSystemMessage.dynamic("working memory turn 1"),
                VanceSystemMessage.dynamic("plan todos turn 1"),
                UserMessage.from("hi")));
        AiChatOptions options = optionsWithBoundary(CacheBoundary.SYSTEM_AND_TOOLS);

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);
        List<Map<String, Object>> system = systemBlocks(body);

        assertThat(system).hasSize(4);
        assertThat(system.get(0)).doesNotContainKey("cache_control");
        // Last STATIC at index 1 — that's where the marker goes.
        assertThat(system.get(1)).containsKey("cache_control");
        // Dynamic tail must NOT carry the marker — they ride outside
        // the cache hash.
        assertThat(system.get(2)).doesNotContainKey("cache_control");
        assertThat(system.get(3)).doesNotContainKey("cache_control");
    }

    @Test
    void noSystemCacheMarker_whenAllDynamic() {
        ChatRequest request = buildRequest(List.of(
                VanceSystemMessage.dynamic("turn-stamp 1"),
                VanceSystemMessage.dynamic("turn-stamp 2"),
                UserMessage.from("hi")));
        AiChatOptions options = optionsWithBoundary(CacheBoundary.SYSTEM_AND_TOOLS);

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);
        List<Map<String, Object>> system = systemBlocks(body);

        assertThat(system).hasSize(2);
        assertThat(system.get(0)).doesNotContainKey("cache_control");
        assertThat(system.get(1)).doesNotContainKey("cache_control");
    }

    @Test
    void cacheBoundaryNone_setsNoMarkerEvenWithStaticBlocks() {
        ChatRequest request = buildRequest(List.of(
                SystemMessage.from("rule one"),
                SystemMessage.from("rule two"),
                UserMessage.from("hi")));
        AiChatOptions options = optionsWithBoundary(CacheBoundary.NONE);

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);
        List<Map<String, Object>> system = systemBlocks(body);

        assertThat(system).hasSize(2);
        assertThat(system.get(0)).doesNotContainKey("cache_control");
        assertThat(system.get(1)).doesNotContainKey("cache_control");
    }

    @Test
    void toolMarkerStillSetIndependently_regardlessOfSystemBlockKind() {
        // All-dynamic system → no system-side marker. Tools-side
        // marker must still fire when CacheBoundary.cachesTools()
        // holds.
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        VanceSystemMessage.dynamic("dynamic-only"),
                        UserMessage.from("call a tool")))
                .toolSpecifications(toolSpec("alpha"), toolSpec("bravo"))
                .build();
        AiChatOptions options = optionsWithBoundary(CacheBoundary.SYSTEM_AND_TOOLS);

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);
        List<Map<String, Object>> system = systemBlocks(body);
        List<Map<String, Object>> tools = toolBlocks(body);

        assertThat(system).hasSize(1);
        assertThat(system.get(0)).doesNotContainKey("cache_control");
        // Tools sorted alphabetically; marker on the last (= "bravo").
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).get("name")).isEqualTo("alpha");
        assertThat(tools.get(0)).doesNotContainKey("cache_control");
        assertThat(tools.get(1).get("name")).isEqualTo("bravo");
        assertThat(tools.get(1)).containsKey("cache_control");
    }

    @Test
    void engineWithoutKindHints_defaultsToStaticForAllBlocks() {
        // Plain SystemMessage.from(...) — no Vance kind. Behaviour
        // must be identical to the legacy single-block flow.
        ChatRequest request = buildRequest(List.of(
                SystemMessage.from("first"),
                SystemMessage.from("second"),
                SystemMessage.from("third"),
                UserMessage.from("hi")));
        AiChatOptions options = optionsWithBoundary(CacheBoundary.SYSTEM_AND_TOOLS);

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);
        List<Map<String, Object>> system = systemBlocks(body);

        assertThat(system).hasSize(3);
        assertThat(system.get(0)).doesNotContainKey("cache_control");
        assertThat(system.get(1)).doesNotContainKey("cache_control");
        // Marker on the last block — the "all STATIC" specialcase.
        assertThat(system.get(2)).containsKey("cache_control");
    }

    @Test
    void thinking_off_omitsBlock() {
        ChatRequest request = buildRequest(List.of(
                SystemMessage.from("rule"),
                UserMessage.from("hi")));
        AiChatOptions options = AiChatOptions.builder()
                .thinkingLevel(ThinkingLevel.OFF)
                .build();

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);

        assertThat(body).doesNotContainKey("thinking");
    }

    @Test
    void thinking_high_emitsEnabledBlockWith16kBudget() {
        ChatRequest request = buildRequest(List.of(
                SystemMessage.from("rule"),
                UserMessage.from("hi")));
        AiChatOptions options = AiChatOptions.builder()
                .thinkingLevel(ThinkingLevel.HIGH)
                .build();

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);

        assertThat(body).containsKey("thinking");
        assertThat(body.get("thinking"))
                .isEqualTo(Map.of("type", "enabled", "budget_tokens", 16000));
    }

    @Test
    void thinking_budgetsRiseMonotonically() {
        Map<String, Object> minimal = AnthropicRequestMapper.buildThinking(ThinkingLevel.MINIMAL);
        Map<String, Object> low = AnthropicRequestMapper.buildThinking(ThinkingLevel.LOW);
        Map<String, Object> medium = AnthropicRequestMapper.buildThinking(ThinkingLevel.MEDIUM);
        Map<String, Object> high = AnthropicRequestMapper.buildThinking(ThinkingLevel.HIGH);

        int budgetMinimal = (int) minimal.get("budget_tokens");
        int budgetLow = (int) low.get("budget_tokens");
        int budgetMedium = (int) medium.get("budget_tokens");
        int budgetHigh = (int) high.get("budget_tokens");

        assertThat(budgetMinimal).isLessThan(budgetLow);
        assertThat(budgetLow).isLessThan(budgetMedium);
        assertThat(budgetMedium).isLessThan(budgetHigh);
        // Anthropic's documented minimum is 1024 tokens.
        assertThat(budgetMinimal).isGreaterThanOrEqualTo(1024);
    }

    @Test
    void userMessage_textOnly_emitsPlainStringContent() {
        ChatRequest request = buildRequest(List.of(
                SystemMessage.from("rule"),
                UserMessage.from("plain question")));

        Map<String, Object> body = AnthropicRequestMapper.buildBody(
                request, AiChatOptions.defaults());
        List<Map<String, Object>> messages = userMessages(body);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("content")).isEqualTo("plain question");
    }

    @Test
    void userMessage_singleTextContentBlock_unwrapsToString() {
        // UserMessage built with one TextContent should render as a
        // bare string for cache-key stability with the legacy path.
        ChatRequest request = buildRequest(List.of(
                UserMessage.from(List.of(TextContent.from("just text")))));

        Map<String, Object> body = AnthropicRequestMapper.buildBody(
                request, AiChatOptions.defaults());
        List<Map<String, Object>> messages = userMessages(body);

        assertThat(messages.get(0).get("content")).isEqualTo("just text");
    }

    @Test
    void userMessage_imageAndText_emitsImageBlockBeforeText() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
        String base64 = Base64.getEncoder().encodeToString(png);
        UserMessage user = UserMessage.from(List.of(
                ImageContent.from(base64, "image/png"),
                TextContent.from("describe this")));
        ChatRequest request = buildRequest(List.of(user));

        Map<String, Object> body = AnthropicRequestMapper.buildBody(
                request, AiChatOptions.defaults());
        List<Map<String, Object>> blocks = userContentBlocks(body, 0);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).get("type")).isEqualTo("image");
        assertThat(blocks.get(1).get("type")).isEqualTo("text");
        assertThat(blocks.get(1).get("text")).isEqualTo("describe this");
        Map<?, ?> source = (Map<?, ?>) blocks.get(0).get("source");
        assertThat(source.get("type")).isEqualTo("base64");
        assertThat(source.get("media_type")).isEqualTo("image/png");
        assertThat(source.get("data")).isEqualTo(base64);
    }

    @Test
    void userMessage_pdfBlock_emitsDocumentType() {
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46};
        String base64 = Base64.getEncoder().encodeToString(pdf);
        UserMessage user = UserMessage.from(List.of(
                PdfFileContent.from(base64, "application/pdf"),
                TextContent.from("summarise")));
        ChatRequest request = buildRequest(List.of(user));

        Map<String, Object> body = AnthropicRequestMapper.buildBody(
                request, AiChatOptions.defaults());
        List<Map<String, Object>> blocks = userContentBlocks(body, 0);

        assertThat(blocks.get(0).get("type")).isEqualTo("document");
        Map<?, ?> source = (Map<?, ?>) blocks.get(0).get("source");
        assertThat(source.get("media_type")).isEqualTo("application/pdf");
    }

    @Test
    void userMessage_attachmentCacheMarkerOnLastAttachment_whenCacheBoundaryActive() {
        byte[] dummy = new byte[]{1, 2, 3};
        String base64 = Base64.getEncoder().encodeToString(dummy);
        UserMessage user = UserMessage.from(List.of(
                ImageContent.from(base64, "image/png"),
                PdfFileContent.from(base64, "application/pdf"),
                TextContent.from("describe both")));
        ChatRequest request = buildRequest(List.of(user));
        AiChatOptions options = AiChatOptions.builder()
                .cacheBoundary(CacheBoundary.SYSTEM_AND_TOOLS)
                .build();

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);
        List<Map<String, Object>> blocks = userContentBlocks(body, 0);

        // Image (idx 0) — no marker
        assertThat(blocks.get(0)).doesNotContainKey("cache_control");
        // PDF (idx 1, last attachment) — marker
        assertThat(blocks.get(1)).containsKey("cache_control");
        // Text (idx 2) — no marker
        assertThat(blocks.get(2)).doesNotContainKey("cache_control");
    }

    @Test
    void userMessage_noAttachmentCacheMarker_whenBoundaryNone() {
        byte[] dummy = new byte[]{1};
        String base64 = Base64.getEncoder().encodeToString(dummy);
        UserMessage user = UserMessage.from(List.of(
                PdfFileContent.from(base64, "application/pdf"),
                TextContent.from("summarise")));
        ChatRequest request = buildRequest(List.of(user));
        AiChatOptions options = AiChatOptions.builder()
                .cacheBoundary(CacheBoundary.NONE)
                .build();

        Map<String, Object> body = AnthropicRequestMapper.buildBody(request, options);
        List<Map<String, Object>> blocks = userContentBlocks(body, 0);

        assertThat(blocks.get(0)).doesNotContainKey("cache_control");
    }

    // ──────────────────── helpers ────────────────────

    private static ChatRequest buildRequest(List<ChatMessage> messages) {
        return ChatRequest.builder()
                .messages(new ArrayList<>(messages))
                .build();
    }

    private static AiChatOptions optionsWithBoundary(CacheBoundary boundary) {
        return AiChatOptions.builder()
                .cacheBoundary(boundary)
                .build();
    }

    private static ToolSpecification toolSpec(String name) {
        return ToolSpecification.builder()
                .name(name)
                .description(name + " description")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("input")
                        .build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> systemBlocks(Map<String, Object> body) {
        Object raw = body.get("system");
        if (raw == null) {
            return List.of();
        }
        return (List<Map<String, Object>>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolBlocks(Map<String, Object> body) {
        Object raw = body.get("tools");
        if (raw == null) {
            return List.of();
        }
        return (List<Map<String, Object>>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> userMessages(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("messages");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> userContentBlocks(
            Map<String, Object> body, int messageIndex) {
        Map<String, Object> message = userMessages(body).get(messageIndex);
        Object content = message.get("content");
        if (content instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        throw new IllegalStateException(
                "user message content is not a block list: " + content);
    }
}
