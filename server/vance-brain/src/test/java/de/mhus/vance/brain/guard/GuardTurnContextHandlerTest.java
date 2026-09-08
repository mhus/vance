package de.mhus.vance.brain.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The turn-prompt replacement mechanics of {@link GuardTurnContextHandler}:
 * with a set prompt every system message is replaced by the guard's text
 * (not additive — the guard owns the whole framing), the non-system
 * messages keep their order, and without a set prompt the handler is a
 * no-op (default: no manipulation).
 */
class GuardTurnContextHandlerTest {

    private final ShootyGuardService service = mock(ShootyGuardService.class);
    private final GuardTurnContextHandler handler = new GuardTurnContextHandler(service);
    private final ThinkEngineContext ctx = mock(ThinkEngineContext.class);

    private ThinkProcessDocument process() {
        return ThinkProcessDocument.builder().id("p1").tenantId("acme").build();
    }

    @Test
    void withoutTurnPrompt_noop() {
        ThinkProcessDocument p = process();
        when(service.turnPromptFor(p)).thenReturn(null);
        List<ChatMessage> messages = List.of(SystemMessage.from("recipe prompt"), UserMessage.from("hello"));

        List<ChatMessage> result = handler.apply(messages, ctx, p);

        assertThat(result).isSameAs(messages);
    }

    @Test
    void withTurnPrompt_replacesAllSystemMessages() {
        ThinkProcessDocument p = process();
        when(service.turnPromptFor(p)).thenReturn("custom framing");
        List<ChatMessage> messages = List.of(
                SystemMessage.from("recipe prompt"),
                UserMessage.from("hello"),
                SystemMessage.from("a second system block (skills, date)"));

        List<ChatMessage> result = handler.apply(messages, ctx, p);

        assertThat(result).hasSize(2);
        assertThat(((SystemMessage) result.get(0)).text()).isEqualTo("custom framing");
        assertThat(((UserMessage) result.get(1)).singleText()).isEqualTo("hello");
    }

    @Test
    void withTurnPrompt_butNoSystemMessage_prepends() {
        // Defensive shape: a request without a system message still gets
        // the replacement at the head — the contract is "the guard text is
        // the framing", not "swap what happens to exist".
        ThinkProcessDocument p = process();
        when(service.turnPromptFor(p)).thenReturn("custom framing");
        List<ChatMessage> messages = List.of(UserMessage.from("hello"));

        List<ChatMessage> result = handler.apply(messages, ctx, p);

        assertThat(result).hasSize(2);
        assertThat(((SystemMessage) result.get(0)).text()).isEqualTo("custom framing");
        assertThat(((UserMessage) result.get(1)).singleText()).isEqualTo("hello");
    }
}
