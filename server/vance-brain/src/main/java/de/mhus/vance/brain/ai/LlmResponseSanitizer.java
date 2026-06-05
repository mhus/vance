package de.mhus.vance.brain.ai;

import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Removes reasoning-mode markup from the LLM's user-visible content
 * when the active model is configured with
 * {@link ModelInfo#stripThinkTags()}.
 *
 * <p>Why this sits between {@code LlmTraceRecorder} and the engines
 * (Arthur, Eddie, Ford, Light-LLM, …): the trace audit must see the
 * raw response (forensic value, training-data analysis, cost
 * accounting), but chat_messages, history-replay, and downstream
 * consumers must see only the final answer. A reasoning chunk like
 * {@code <think>let me think</think>} that leaks into the assistant
 * message turns into:
 *
 * <ul>
 *   <li>noise in the chat UI</li>
 *   <li>the model's internal monologue replayed as history into the
 *       next turn → drift / confusion</li>
 *   <li>broken inline kinds: the {@code ```mindmap} fence ends up
 *       wrapping the thinking text instead of the actual hierarchy</li>
 * </ul>
 *
 * <p>Supported markup conventions (all stripped when the model's
 * {@code stripThinkTags} flag is true):
 *
 * <ul>
 *   <li><b>Qwen3 / DeepSeek-R1 / Granite-Reasoning</b> —
 *       {@code <think>…</think>} blocks anywhere in the text.</li>
 *   <li><b>GPT-OSS (harmony format)</b> — {@code <|channel|>analysis}
 *       and {@code <|channel|>commentary} segments. The
 *       {@code <|channel|>final} segment marker is kept as the
 *       user-visible content boundary; its content is the cleaned
 *       output. Stripping is best-effort — gpt-oss adapters in
 *       langchain4j vary in how they expose these markers, so we
 *       handle the verbatim text leak case here as a safety net.</li>
 * </ul>
 *
 * <p>The sanitizer is stateless and idempotent — feeding a clean
 * input returns it unchanged.
 */
@Component
public class LlmResponseSanitizer {

    /** Qwen-style: {@code <think>…</think>}, multi-line, ungreedy. */
    private static final Pattern THINK_TAG = Pattern.compile(
            "(?is)<think>.*?</think>");

    /**
     * Harmony "analysis"/"commentary" channels — text from the start
     * of an {@code <|channel|>analysis} (or commentary) marker up to
     * either the next channel marker or the end of the input.
     */
    private static final Pattern HARMONY_NON_FINAL = Pattern.compile(
            "(?is)<\\|channel\\|>(?:analysis|commentary)(?:<\\|message\\|>)?"
                    + ".*?(?=<\\|channel\\|>|<\\|end\\|>|\\z)");

    /** Standalone harmony delimiters that survive after channel-stripping. */
    private static final List<Pattern> HARMONY_LEFTOVERS = List.of(
            Pattern.compile("<\\|channel\\|>(?:final)(?:<\\|message\\|>)?"),
            Pattern.compile("<\\|end\\|>"),
            Pattern.compile("<\\|message\\|>"),
            Pattern.compile("<\\|start\\|>"));

    /**
     * Returns {@code content} with reasoning markup stripped when
     * {@code modelInfo.stripThinkTags()} is {@code true}; otherwise
     * returns the input unchanged. {@code null} stays {@code null}.
     *
     * <p>Whitespace cleanup: after stripping, collapses runs of
     * three-or-more newlines to two, and trims the result. This keeps
     * downstream parsing (e.g. fenced-block extraction) stable when
     * the reasoning block was surrounded by blank lines.
     */
    public @Nullable String strip(@Nullable String content, @Nullable ModelInfo modelInfo) {
        if (content == null) return null;
        if (modelInfo == null || !modelInfo.stripThinkTags()) return content;
        return stripUnconditional(content);
    }

    /**
     * Same logic as {@link #strip} but always applied. Test-friendly
     * entry point; callers that read the flag themselves use this to
     * drive the cleanup deterministically.
     */
    public String stripUnconditional(String content) {
        if (content == null || content.isEmpty()) return content;
        String out = THINK_TAG.matcher(content).replaceAll("");
        out = HARMONY_NON_FINAL.matcher(out).replaceAll("");
        for (Pattern p : HARMONY_LEFTOVERS) {
            out = p.matcher(out).replaceAll("");
        }
        // Collapse 3+ blank lines (artefact of stripped blocks that
        // sat on their own lines) down to a single blank line.
        out = out.replaceAll("\\n{3,}", "\n\n");
        return out.strip();
    }
}
