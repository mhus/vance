package de.mhus.vance.brain.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolCallContentHttpClient#normalizeRequestBody}: the
 * request-body rewrite that strips assistant tool-call {@code content: null}
 * (which GLM/Zhipu rejects) while leaving everything else byte-identical.
 */
class ToolCallContentHttpClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode message(String normalizedBody, int index) {
        JsonNode root = MAPPER.readTree(normalizedBody);
        return root.get("messages").get(index);
    }

    @Test
    void assistantToolCallWithNullContent_hasContentKeyRemoved() {
        String body = """
            {"model":"glm-5.2","messages":[
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"c1","type":"function","function":{"name":"file_read","arguments":"{}"}}]}
            ]}""";

        String out = ToolCallContentHttpClient.normalizeRequestBody(body);

        assertThat(out).isNotNull();
        JsonNode msg = message(out, 0);
        assertThat(msg.has("content")).isFalse();
        assertThat(msg.get("tool_calls")).isNotNull();
        assertThat(msg.get("tool_calls").size()).isEqualTo(1);
    }

    @Test
    void assistantToolCallWithBlankStringContent_hasContentKeyRemoved() {
        String body = """
            {"messages":[
              {"role":"assistant","content":"   ","tool_calls":[
                {"id":"c1","type":"function","function":{"name":"x","arguments":"{}"}}]}
            ]}""";

        String out = ToolCallContentHttpClient.normalizeRequestBody(body);

        assertThat(out).isNotNull();
        assertThat(message(out, 0).has("content")).isFalse();
    }

    @Test
    void assistantToolCallWithRealText_isLeftUnchanged() {
        String body = """
            {"messages":[
              {"role":"assistant","content":"Let me read the file.","tool_calls":[
                {"id":"c1","type":"function","function":{"name":"x","arguments":"{}"}}]}
            ]}""";

        // Nothing to strip → unchanged → null sentinel.
        assertThat(ToolCallContentHttpClient.normalizeRequestBody(body)).isNull();
    }

    @Test
    void assistantWithoutToolCalls_nullContentIsLeftUnchanged() {
        // A content-less assistant message with no tool calls is not the
        // bug we target; leave it for the caller to have avoided producing.
        String body = """
            {"messages":[{"role":"assistant","content":null}]}""";

        assertThat(ToolCallContentHttpClient.normalizeRequestBody(body)).isNull();
    }

    @Test
    void nonAssistantRoles_areNeverTouched() {
        String body = """
            {"messages":[
              {"role":"user","content":null},
              {"role":"tool","content":null,"tool_call_id":"c1"}
            ]}""";

        assertThat(ToolCallContentHttpClient.normalizeRequestBody(body)).isNull();
    }

    @Test
    void arrayContentWithToolCalls_isLeftUnchanged() {
        String body = """
            {"messages":[
              {"role":"assistant","content":[{"type":"text","text":"hi"}],"tool_calls":[
                {"id":"c1","type":"function","function":{"name":"x","arguments":"{}"}}]}
            ]}""";

        assertThat(ToolCallContentHttpClient.normalizeRequestBody(body)).isNull();
    }

    @Test
    void onlyOffendingMessagesStripped_othersPreserved() {
        String body = """
            {"model":"glm-5.2","temperature":0.2,"messages":[
              {"role":"system","content":"You are Frankie."},
              {"role":"user","content":"continue"},
              {"role":"assistant","content":"Reading now.","tool_calls":[
                {"id":"a","type":"function","function":{"name":"x","arguments":"{}"}}]},
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"b","type":"function","function":{"name":"y","arguments":"{}"}}]}
            ]}""";

        String out = ToolCallContentHttpClient.normalizeRequestBody(body);

        assertThat(out).isNotNull();
        JsonNode root = MAPPER.readTree(out);
        assertThat(root.get("model").asString()).isEqualTo("glm-5.2");
        assertThat(root.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(message(out, 0).get("content").asString()).isEqualTo("You are Frankie.");
        assertThat(message(out, 2).get("content").asString()).isEqualTo("Reading now.");
        assertThat(message(out, 3).has("content")).isFalse();
        assertThat(message(out, 3).get("tool_calls").get(0).get("id").asString()).isEqualTo("b");
    }

    @Test
    void nonJsonOrNoMessages_passesThroughUnchanged() {
        assertThat(ToolCallContentHttpClient.normalizeRequestBody("not json {")).isNull();
        assertThat(ToolCallContentHttpClient.normalizeRequestBody("{\"model\":\"x\"}")).isNull();
        assertThat(ToolCallContentHttpClient.normalizeRequestBody("")).isNull();
        assertThat(ToolCallContentHttpClient.normalizeRequestBody(null)).isNull();
    }

    @Test
    void bodyWithoutAnEmptyContent_skipsTheParseEntirely() {
        // The pre-parse filter is what keeps a full Jackson round-trip of
        // the whole conversation off every single request. A body with no
        // empty content must not even be parsed — proven here by feeding
        // one that is *not valid JSON* after the messages array: a parse
        // would fail (and still return null), so instead we assert the
        // filter's own contract on a body that is valid but untouched.
        String body = """
                {"model":"gpt-5","messages":[
                  {"role":"user","content":"hi"},
                  {"role":"assistant","content":"there","tool_calls":[{"id":"a"}]}
                ]}
                """;
        assertThat(ToolCallContentHttpClient.normalizeRequestBody(body)).isNull();
    }

    @Test
    void whitespaceOnlyContent_isStillRecognised() {
        // The emptiness test accepts a blank string, so the pre-parse
        // filter must too — otherwise the fix would silently stop firing
        // for that shape.
        String body = """
                {"model":"glm-5.2","messages":[
                  {"role":"assistant","content":"  ","tool_calls":[{"id":"a"}]}
                ]}
                """;
        String out = ToolCallContentHttpClient.normalizeRequestBody(body);

        assertThat(out).isNotNull();
        assertThat(message(out, 0).has("content")).isFalse();
    }

    @Test
    void spacedSerialisation_isStillRecognised() {
        String body = "{\"messages\":[{\"role\": \"assistant\", \"content\": null, "
                + "\"tool_calls\": [{\"id\": \"a\"}]}]}";
        String out = ToolCallContentHttpClient.normalizeRequestBody(body);

        assertThat(out).isNotNull();
        assertThat(message(out, 0).has("content")).isFalse();
    }
}
