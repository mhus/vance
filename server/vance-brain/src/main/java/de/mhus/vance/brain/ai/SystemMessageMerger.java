package de.mhus.vance.brain.ai;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

/**
 * Collapses consecutive {@link SystemMessage}s into a single one.
 *
 * <p><b>Why this exists.</b> Engines emit the system prompt as several
 * blocks — Arthur alone appends up to nine (base prompt, skill section,
 * tool catalog, memory, persona, facts, active workers, todos, tool
 * hints). That split is deliberate and valuable: it is what lets
 * {@code AnthropicRequestMapper} place the {@code cache_control} marker
 * on the last STATIC block so the big stable prefix stays cached while
 * the volatile tail rides outside the cache hash (see
 * {@code specification/public/prompt-caching.md} §5a).
 *
 * <p>Some endpoints cannot take that shape. Ollama's {@code glimmer}
 * renderer ({@code model/renderers/glimmer.go}, v0.32.11) writes the
 * COMPLETE tool manifest once per system message — measured exactly
 * linear: one block one copy, two blocks two copies, three blocks
 * three. With a 286-tool surface that is ~36.8K tokens per copy, and
 * four blocks push a 131K window over the edge. The peers
 * ({@code renderer=qwen3.5}, {@code renderer=gemma4}) write it once per
 * request and are unaffected.
 *
 * <p>Merging is therefore opt-in per model ({@code mergeSystemMessages}
 * in the model catalog) and applied in the provider layer, never
 * globally: on Anthropic the separation carries real money, so
 * flattening everywhere would trade a working cost optimisation for one
 * broken renderer.
 *
 * <p>The merge preserves content and order verbatim, joining blocks
 * with a blank line. Ollama reuses its KV prefix by token order, not by
 * message boundary, so nothing is lost there.
 *
 * <p>Investigation and measurements:
 * {@code planning/model-context-inflation-lab.md}.
 */
public final class SystemMessageMerger {

    private static final String JOIN = "\n\n";

    private SystemMessageMerger() {
    }

    /**
     * Returns {@code request} unchanged when it carries fewer than two
     * system messages, otherwise a copy whose consecutive system
     * messages are merged.
     */
    public static ChatRequest merge(ChatRequest request) {
        List<ChatMessage> merged = mergeMessages(request.messages());
        if (merged == request.messages()) {
            return request;
        }
        return ChatRequest.builder()
                .messages(merged)
                .parameters(request.parameters())
                .build();
    }

    /**
     * Merges runs of consecutive system messages. Returns the input
     * instance itself when there is nothing to merge, so callers can
     * skip rebuilding the request.
     */
    public static List<ChatMessage> mergeMessages(List<ChatMessage> messages) {
        int systemCount = 0;
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage) {
                systemCount++;
            }
        }
        if (systemCount < 2) {
            return messages;
        }

        List<ChatMessage> out = new ArrayList<>(messages.size());
        StringBuilder run = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage s) {
                if (!run.isEmpty()) {
                    run.append(JOIN);
                }
                run.append(s.text());
                continue;
            }
            flush(out, run);
            out.add(m);
        }
        flush(out, run);
        return out;
    }

    private static void flush(List<ChatMessage> out, StringBuilder run) {
        if (run.isEmpty()) {
            return;
        }
        out.add(SystemMessage.from(run.toString()));
        run.setLength(0);
    }
}
