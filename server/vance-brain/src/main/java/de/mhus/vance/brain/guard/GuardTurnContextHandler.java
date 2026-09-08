package de.mhus.vance.brain.guard;

import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.TurnContextHandler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Applies the turn-prompt replacement a START guard set via
 * {@code vance.guard.setTurnPrompt(text)}: <b>replaces</b> the request's
 * system message(s) — recipe prompt, skill blocks, date context — with the
 * guard's text. Not additive, deliberately: the START guard that replaces
 * the prompt owns the whole framing of the turn; anything it still wants
 * (skills it activated, date) it must fold into the text itself.
 *
 * <p>Engine coverage is automatic: every engine that fires START guards
 * (Ford, Frankie, the {@code StructuredActionEngine} family — Trillian via
 * inheritance) routes its LLM requests through the
 * {@code TurnContextHandlerRegistry} this handler plugs into. The
 * injection is ephemeral request-only augmentation per the
 * {@link TurnContextHandler} contract — the canonical conversation is
 * never mutated, so the replacement never leaks into history, replays or
 * compaction.
 *
 * <p>Default is no manipulation: without a set prompt the handler is a
 * no-op. One text per turn (last setTurnPrompt call wins); the store is
 * cleared at the next genuine user turn, and a guard-injected follow-up
 * turn (sender {@code _guard}) inherits the replacement — it is the same
 * work unit. See {@code planning/shooty.md} §8.
 */
@Component
public class GuardTurnContextHandler implements TurnContextHandler {

    private final ShootyGuardService guardService;

    public GuardTurnContextHandler(ShootyGuardService guardService) {
        this.guardService = guardService;
    }

    @Override
    public List<ChatMessage> apply(List<ChatMessage> messages, ThinkEngineContext ctx, ThinkProcessDocument process) {
        String turnPrompt = guardService.turnPromptFor(process);
        if (turnPrompt == null) {
            return messages;
        }
        // Ephemeral replacement — fresh list, never mutate the passed one.
        // Every SystemMessage goes: the guard's text is the whole framing.
        List<ChatMessage> replaced = new ArrayList<>(messages.size());
        replaced.add(SystemMessage.from(turnPrompt));
        for (ChatMessage m : messages) {
            if (!(m instanceof SystemMessage)) {
                replaced.add(m);
            }
        }
        return replaced;
    }
}
