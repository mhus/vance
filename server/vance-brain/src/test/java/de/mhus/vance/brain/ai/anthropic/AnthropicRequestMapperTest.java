package de.mhus.vance.brain.ai.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.SystemBlockKind;
import de.mhus.vance.brain.ai.VanceSystemMessage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.ArrayList;
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
}
