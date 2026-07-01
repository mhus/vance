package de.mhus.vance.brain.ai.parser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiToolCodeMessageParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final GeminiToolCodeMessageParser parser = new GeminiToolCodeMessageParser(null);

    @Test
    void name_isStableIdentifier() {
        assertThat(parser.name()).isEqualTo("gemini-tool-code");
    }

    @Test
    void synthesizesCall_fromBareToolCodeLine() {
        // Shape as rendered in vance-brain1.log (2026-07-01): a bare
        // `tool_code:` line with kwargs and a nested `blocks=[…]` body.
        String text = "I will now create the page for your use case.\n"
                + "tool_code: workpage_create(path=\"apps/test-workspace/noten-rechner.workpage.md\", "
                + "blocks=[{\"type\":\"heading\",\"level\":1,\"content\":\"Noten Rechner\"},"
                + "{\"type\":\"paragraph\",\"content\":\"Hier eingeben.\"}])\n"
                + "I have created the page.";
        ChatResponse out = parser.parse(responseWithText(text));

        assertThat(out.aiMessage().hasToolExecutionRequests()).isTrue();
        ToolExecutionRequest call = out.aiMessage().toolExecutionRequests().get(0);
        assertThat(call.name()).isEqualTo("workpage_create");
        assertThat(call.id()).startsWith("gemini-");

        JsonNode args = readJson(call.arguments());
        assertThat(args.get("path").asString())
                .isEqualTo("apps/test-workspace/noten-rechner.workpage.md");
        assertThat(args.get("blocks").isArray()).isTrue();
        assertThat(args.get("blocks").size()).isEqualTo(2);
        assertThat(args.get("blocks").get(0).get("content").asString()).isEqualTo("Noten Rechner");
    }

    @Test
    void synthesizesCall_fromFencedPrintWrappedBlock() {
        // Gemini's native shape: a fenced ```tool_code``` block wrapping
        // the call in print(...).
        String text = "Sure, creating it now.\n\n"
                + "```tool_code\n"
                + "print(workpage_create(path=\"apps/x/page\", "
                + "blocks=[{\"type\":\"heading\",\"content\":\"Hi\"}]))\n"
                + "```\n";
        ChatResponse out = parser.parse(responseWithText(text));

        ToolExecutionRequest call = out.aiMessage().toolExecutionRequests().get(0);
        assertThat(call.name()).isEqualTo("workpage_create");
        JsonNode args = readJson(call.arguments());
        assertThat(args.get("path").asString()).isEqualTo("apps/x/page");
        assertThat(args.get("blocks").get(0).get("content").asString()).isEqualTo("Hi");
    }

    @Test
    void synthesizesMultipleCalls_inDocumentOrder() {
        String text = "tool_code: doc_read(path=\"a/_app.yaml\")\n"
                + "tool_code: workpage_create(path=\"a/page\")\n";
        ChatResponse out = parser.parse(responseWithText(text));

        List<ToolExecutionRequest> calls = out.aiMessage().toolExecutionRequests();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).name()).isEqualTo("doc_read");
        assertThat(calls.get(1).name()).isEqualTo("workpage_create");
    }

    @Test
    void acceptsBodyThatIsAlreadyAJsonObject() {
        String text = "```tool_code\nworkpage_create({\"path\": \"a/page\", \"title\": \"T\"})\n```";
        ChatResponse out = parser.parse(responseWithText(text));

        JsonNode args = readJson(out.aiMessage().toolExecutionRequests().get(0).arguments());
        assertThat(args.get("path").asString()).isEqualTo("a/page");
        assertThat(args.get("title").asString()).isEqualTo("T");
    }

    @Test
    void preservesStructuredToolCalls_whenAlreadyClean() {
        // Defensive: a clean turn with real tool calls must pass through even
        // if the (irrelevant) text happens to mention tool_code.
        ToolExecutionRequest original = ToolExecutionRequest.builder()
                .id("real-id").name("doc_create").arguments("{}").build();
        ChatResponse raw = ChatResponse.builder()
                .aiMessage(AiMessage.from("tool_code: mentioned", List.of(original))).build();

        assertThat(parser.parse(raw)).isSameAs(raw);
    }

    @Test
    void passesThrough_whenNoToolCodeMarker() {
        ChatResponse raw = responseWithText("Just prose that calls foo(bar) but no marker.");
        assertThat(parser.parse(raw)).isSameAs(raw);
    }

    @Test
    void passesThrough_whenBodyNotParseable() {
        // Marker present but the body is not valid JSON even after conversion.
        ChatResponse raw = responseWithText("tool_code: workpage_create(path=not_quoted_value)");
        assertThat(parser.parse(raw)).isSameAs(raw);
    }

    @Test
    void passesThrough_whenAiMessageIsNull() {
        assertThat(parser.parse(null)).isNull();
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
