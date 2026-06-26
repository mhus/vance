package de.mhus.vance.brain.ai.parser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageParserRegistryTest {

    @Test
    void lookup_byName_returnsRegisteredParser() {
        MessageParser p = new StubParser("foo");
        MessageParserRegistry registry = new MessageParserRegistry(List.of(p));
        assertThat(registry.get("foo")).contains(p);
    }

    @Test
    void unknownName_returnsEmpty() {
        MessageParserRegistry registry = new MessageParserRegistry(
                List.of(new StubParser("foo")));
        assertThat(registry.get("missing")).isEmpty();
    }

    @Test
    void nullOrBlankName_returnsEmpty() {
        MessageParserRegistry registry = new MessageParserRegistry(
                List.of(new StubParser("foo")));
        assertThat(registry.get(null)).isEmpty();
        assertThat(registry.get("")).isEmpty();
        assertThat(registry.get("   ")).isEmpty();
    }

    @Test
    void duplicateNames_firstWins() {
        MessageParser a = new StubParser("dup");
        MessageParser b = new StubParser("dup");
        MessageParserRegistry registry = new MessageParserRegistry(List.of(a, b));
        assertThat(registry.get("dup")).contains(a);
    }

    @Test
    void blankNameParser_ignored() {
        MessageParser blank = new StubParser("");
        MessageParser real = new StubParser("real");
        MessageParserRegistry registry = new MessageParserRegistry(List.of(blank, real));
        assertThat(registry.names()).containsExactly("real");
    }

    private record StubParser(String name) implements MessageParser {
        @Override
        public ChatResponse parse(ChatResponse raw) {
            return raw;
        }
    }
}
