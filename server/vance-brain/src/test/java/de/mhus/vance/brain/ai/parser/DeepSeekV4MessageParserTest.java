package de.mhus.vance.brain.ai.parser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the SPI hull around {@code ToolArgumentNormalizer}.
 * The normalizer itself has its own unit tests; here we only verify
 * that the parser plumbs requests and responses through it correctly.
 */
class DeepSeekV4MessageParserTest {

    private final DeepSeekV4MessageParser parser = new DeepSeekV4MessageParser(null);

    @Test
    void name_isStableIdentifier() {
        assertThat(parser.name()).isEqualTo("deepseek-v4");
    }

    @Test
    void cleanResponse_passesThroughUnchanged() {
        ChatResponse raw = ChatResponse.builder()
                .aiMessage(AiMessage.from("plain answer"))
                .build();
        assertThat(parser.parse(raw)).isSameAs(raw);
    }

    @Test
    void trimsTrailingGarbageInToolArguments() {
        ToolExecutionRequest dirty = ToolExecutionRequest.builder()
                .id("c1").name("manual_list")
                .arguments("{} \"manual_list\"").build();
        ChatResponse raw = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok", List.of(dirty)))
                .build();

        ChatResponse out = parser.parse(raw);

        assertThat(out.aiMessage().toolExecutionRequests()).hasSize(1);
        assertThat(out.aiMessage().toolExecutionRequests().get(0).arguments())
                .isEqualTo("{}");
        assertThat(out.aiMessage().toolExecutionRequests().get(0).id()).isEqualTo("c1");
    }
}
