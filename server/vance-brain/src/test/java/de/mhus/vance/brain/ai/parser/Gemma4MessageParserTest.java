package de.mhus.vance.brain.ai.parser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class Gemma4MessageParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Gemma4MessageParser parser = new Gemma4MessageParser(null);

    @Test
    void name_isStableIdentifier() {
        assertThat(parser.name()).isEqualTo("gemma4");
    }

    @Test
    void parsesInlineArthurAction_fromLoggedGemmaOutput() {
        // Verbatim from vance-brain1.log line 2072 — gemma-4-26b-a4b-it
        // running Arthur in LM Studio: chat prelude followed by the
        // tool call as text with <|"|> as quote token.
        String body = "Hallo! Wie kann ich dir heute helfen?\n\n"
                + "arthur_action{message:<|\"|>Hallo! Wie kann ich dir heute helfen?<|\"|>,"
                + "reason:<|\"|>The user greeted me in German, "
                + "so I am responding in German to match their language.<|\"|>,"
                + "type:<|\"|>ANSWER<|\"|>}";
        ChatResponse out = parser.parse(responseWithText(body));

        assertThat(out.aiMessage().hasToolExecutionRequests()).isTrue();
        ToolExecutionRequest call = out.aiMessage().toolExecutionRequests().get(0);
        assertThat(call.name()).isEqualTo("arthur_action");
        assertThat(call.id()).startsWith("gemma4-");

        JsonNode args = readJson(call.arguments());
        assertThat(args.get("message").asString())
                .isEqualTo("Hallo! Wie kann ich dir heute helfen?");
        assertThat(args.get("type").asString()).isEqualTo("ANSWER");
        assertThat(args.get("reason").asString())
                .contains("responding in German");
    }

    @Test
    void preservesStructuredToolCalls_whenAlreadyClean() {
        // Defensive: a clean turn — engines must see the original,
        // not a synthesized duplicate.
        ToolExecutionRequest original = ToolExecutionRequest.builder()
                .id("real-id").name("manual_list").arguments("{}").build();
        ChatResponse raw = ChatResponse.builder()
                .aiMessage(AiMessage.from("ignored", List.of(original))).build();

        ChatResponse out = parser.parse(raw);
        assertThat(out).isSameAs(raw);
    }

    @Test
    void passesThroughText_whenNoToolCallPatternMatches() {
        ChatResponse raw = responseWithText("Just a normal sentence, no tool call here.");
        ChatResponse out = parser.parse(raw);
        assertThat(out).isSameAs(raw);
    }

    @Test
    void passesThroughText_whenBodyIsNotParseableJson() {
        // Pattern matches but the body is malformed even after
        // de-tokenizing — defensive fall-back, no synthesis.
        ChatResponse raw = responseWithText(
                "arthur_action{not a valid body at all <|\"|>{garbage<|\"|>}");
        ChatResponse out = parser.parse(raw);
        assertThat(out).isSameAs(raw);
    }

    @Test
    void usesLastMatch_whenModelEmitsAnotherCallAfterProse() {
        // Gemma sometimes prefixes the tool call with a tool-name
        // mention in prose. Last match wins so we don't catch the
        // quoted reference.
        String body = "I will now emit one arthur_action{message: <|\"|>x<|\"|>}\n\n"
                + "arthur_action{message:<|\"|>final<|\"|>,type:<|\"|>ANSWER<|\"|>}";
        ChatResponse out = parser.parse(responseWithText(body));
        JsonNode args = readJson(
                out.aiMessage().toolExecutionRequests().get(0).arguments());
        assertThat(args.get("message").asString()).isEqualTo("final");
        assertThat(args.get("type").asString()).isEqualTo("ANSWER");
    }

    @Test
    void passesThroughText_whenAiMessageIsNull() {
        ChatResponse out = parser.parse(null);
        assertThat(out).isNull();
    }

    private static ChatResponse responseWithText(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static JsonNode readJson(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (RuntimeException e) {
            throw new AssertionError("could not parse synthesized args: " + raw, e);
        }
    }
}
