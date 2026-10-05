package de.mhus.vance.brain.ai.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Wire-equivalence tests for the map → {@link MessageParam} translation. Each case
 * asserts that the typed SDK object serializes back to <b>exactly</b> the map the
 * request mapper produced — the map is the contract, the typed form is only the SDK's
 * entry ticket. A drift here would silently change what Anthropic receives.
 */
class AnthropicMessageParamsTest {

    // ──────────────────── messages ────────────────────

    @Test
    void trailingSystemMessage_roleSystem_stringContent() {
        Map<String, Object> raw = message("system", "working memory: round one");

        MessageParam param = AnthropicMessageParams.messageParams(List.of(raw)).get(0);

        assertThat(param.role()).isEqualTo(MessageParam.Role.SYSTEM);
        assertThat(param.content().asString()).isEqualTo("working memory: round one");
        assertWireEquivalence(raw);
    }

    @Test
    void userMessage_stringContent_staysStringContent() {
        Map<String, Object> raw = message("user", "plain question");

        assertWireEquivalence(raw);
    }

    @Test
    void mixedArray_preservesOrder() {
        List<MessageParam> params = AnthropicMessageParams.messageParams(
                List.of(message("user", "first"), message("assistant", "second"), message("system", "tail")));

        assertThat(params).hasSize(3);
        assertThat(params.get(0).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(params.get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(params.get(2).role()).isEqualTo(MessageParam.Role.SYSTEM);
    }

    @Test
    void messageWithoutRole_failsLoudly() {
        assertThatThrownBy(() -> AnthropicMessageParams.messageParams(List.of(Map.of("content", "x"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("role");
    }

    // ──────────────────── content blocks ────────────────────

    @Test
    void textBlock_historyMarkerWithTtl() {
        Map<String, Object> raw = message(
                "user",
                List.of(Map.of(
                        "type", "text",
                        "text", "Say READY.",
                        "cache_control", Map.of("type", "ephemeral", "ttl", "1h"))));

        assertWireEquivalence(raw);
    }

    @Test
    void textBlock_markerWithoutTtl() {
        Map<String, Object> raw = message(
                "user", List.of(Map.of("type", "text", "text", "hi", "cache_control", Map.of("type", "ephemeral"))));

        assertWireEquivalence(raw);
    }

    @Test
    void imageBlock_base64AndUrl() {
        Map<String, Object> base64 = block(
                "image",
                Map.of("type", "base64", "media_type", "image/png", "data", "aGVsbG8="),
                Map.of("type", "ephemeral"));
        Map<String, Object> url = block("image", Map.of("type", "url", "url", "https://example.com/x.png"));

        assertWireEquivalence(message("user", List.of(base64, url)));
    }

    @Test
    void documentBlock_base64AndUrl() {
        Map<String, Object> base64 =
                block("document", Map.of("type", "base64", "media_type", "application/pdf", "data", "aGVsbG8="));
        Map<String, Object> url = block("document", Map.of("type", "url", "url", "https://example.com/x.pdf"));

        assertWireEquivalence(message("user", List.of(base64, url)));
    }

    @Test
    void toolUseBlock_parsedInputObject() {
        Map<String, Object> raw = message(
                "assistant",
                List.of(Map.of(
                        "type",
                        "tool_use",
                        "id",
                        "call_1",
                        "name",
                        "tool_a",
                        "input",
                        Map.of("arg", "value", "n", 2))));

        assertWireEquivalence(raw);
    }

    @Test
    void toolUseBlock_withMarker() {
        // The history breakpoint lands on a tool_use block whenever the
        // request ends on an assistant tool-call message — the marker must
        // survive the typed translation like every other block type.
        Map<String, Object> raw = message(
                "assistant",
                List.of(Map.of(
                        "type",
                        "tool_use",
                        "id",
                        "call_1",
                        "name",
                        "tool_a",
                        "input",
                        Map.of("arg", "value"),
                        "cache_control",
                        Map.of("type", "ephemeral"))));

        assertWireEquivalence(raw);
    }

    @Test
    void toolResultBlock_withMarker() {
        Map<String, Object> raw = message(
                "user",
                List.of(Map.of(
                        "type",
                        "tool_result",
                        "tool_use_id",
                        "call_1",
                        "content",
                        "42",
                        "cache_control",
                        Map.of("type", "ephemeral"))));

        assertWireEquivalence(raw);
    }

    @Test
    void unknownBlockType_failsLoudly() {
        Map<String, Object> raw = message("user", List.of(Map.of("type", "audio", "data", "xxx")));

        assertThatThrownBy(() -> AnthropicMessageParams.messageParams(List.of(raw)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audio");
    }

    // ──────────────────── helpers ────────────────────

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private static Map<String, Object> block(String type, Map<String, Object> source) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", type);
        block.put("source", source);
        return block;
    }

    private static Map<String, Object> block(
            String type, Map<String, Object> source, Map<String, Object> cacheControl) {
        Map<String, Object> block = block(type, source);
        block.put("cache_control", cacheControl);
        return block;
    }

    /**
     * The typed param must serialize back to the mapper's map — byte-for-byte the same
     * JSON, key order aside. This is what proves the translation is loss-free.
     */
    private static void assertWireEquivalence(Map<String, Object> rawMessage) {
        MessageParam param =
                AnthropicMessageParams.messageParams(List.of(rawMessage)).get(0);
        Map<?, ?> wire = ObjectMappers.jsonMapper().convertValue(param, Map.class);
        assertThat(wire).isEqualTo(rawMessage);
    }
}
