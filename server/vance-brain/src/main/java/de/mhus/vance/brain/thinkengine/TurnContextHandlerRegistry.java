package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs every {@link TurnContextHandler} bean over a turn's message list
 * before an LLM request. No-op when no handlers are registered. Engines
 * inject this once and call {@link #apply} at each request-build site; the
 * research-pressure handler and any future per-turn context handler plug
 * in without the engines carrying feature-specific code.
 */
@Service
@Slf4j
public class TurnContextHandlerRegistry {

    private final List<TurnContextHandler> handlers;

    public TurnContextHandlerRegistry(List<TurnContextHandler> handlers) {
        this.handlers = handlers;
    }

    /**
     * Applies all handlers in registration order. Each may return an
     * augmented copy; a throwing handler is logged and skipped so a
     * broken handler never breaks the engine turn.
     */
    public List<ChatMessage> apply(
            List<ChatMessage> messages, ThinkEngineContext ctx, ThinkProcessDocument process) {
        List<ChatMessage> current = messages;
        for (TurnContextHandler h : handlers) {
            try {
                List<ChatMessage> next = h.apply(current, ctx, process);
                if (next != null) {
                    current = next;
                }
            } catch (RuntimeException e) {
                log.warn("TurnContextHandler {} threw (skipping): {}",
                        h.getClass().getSimpleName(), e.toString());
            }
        }
        return current;
    }
}
