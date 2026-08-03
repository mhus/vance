package de.mhus.vance.brain.thinkengine.action;

import dev.langchain4j.data.message.AiMessage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/**
 * Extracts the genuine reasoning ("thoughts") of an assistant reply —
 * and only that, never the model's free-text answer.
 *
 * <p>Two real sources of reasoning:
 * <ul>
 *   <li>Separate-channel models (GLM/Zhipu, DeepSeek-style OpenAI
 *       gateways) return it via {@link AiMessage#thinking()} (parsed
 *       from {@code reasoning_content} when the model is built with
 *       {@code returnThinking(true)}).</li>
 *   <li>Inline models (Qwen3 / DeepSeek-R1 / Granite) wrap it in
 *       {@code <think>…</think>} inside the reply text.</li>
 * </ul>
 *
 * <p>A plain free-text answer with no reasoning markup yields {@code ""}:
 * it is NOT reasoning and must never be routed into the thoughts channel
 * (doing so duplicates the answer and leaks correction echoes / stray
 * tags into "thoughts" — the original bug this guards against).
 */
@NullMarked
public final class ReasoningExtractor {

    /** Matches an inline {@code <think>…</think>} reasoning block. */
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>(.*?)</think>");

    private ReasoningExtractor() {}

    /** The reply's reasoning, or {@code ""} when it carries none. */
    public static String extract(AiMessage reply) {
        String thinking = reply.thinking();
        if (thinking != null && !thinking.isBlank()) {
            return thinking.strip();
        }
        String text = reply.text();
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = THINK_BLOCK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1);
            if (inner != null && !inner.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(inner.strip());
            }
        }
        return sb.toString();
    }
}
