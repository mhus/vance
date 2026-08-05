package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;

/**
 * Pluggable per-request context handler. Engines that run a tool loop
 * (Ford, the {@code StructuredActionEngine} action loop, Frankie) call
 * the {@link TurnContextHandlerRegistry} once <em>before each LLM
 * request</em> with the accumulated message list. A handler may return an
 * augmented copy to inject dynamic, within-turn context — e.g. escalating
 * tool-use pressure that grows as the loop makes more of a given tool
 * call.
 *
 * <p>Unlike the {@code SystemPromptComposer} / addon-fragment registry
 * (which renders once at turn start), this runs on the <em>live</em>
 * conversation, so a handler sees what happened earlier in the same turn.
 *
 * <p><b>Contract:</b> handlers must NOT mutate the passed list — return
 * either the same list (no-op) or a fresh augmented copy. Anything a
 * handler appends is ephemeral request-context: it is seen by the model
 * for that one request and never persists into the canonical
 * conversation, so injections do not accumulate across iterations.
 */
public interface TurnContextHandler {

    /**
     * @param messages the live, accumulated message list for this turn
     *                 (do not mutate)
     * @param ctx      the engine context
     * @param process  the running process
     * @return the messages to send for this request — the same list, or a
     *         fresh augmented copy
     */
    List<ChatMessage> apply(
            List<ChatMessage> messages, ThinkEngineContext ctx, ThinkProcessDocument process);
}
