package de.mhus.vance.brain.ai.parser;

import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Model-specific post-processor for {@link ChatResponse}s. Sits between
 * the raw provider response and the engine layer, and is allowed to
 * synthesize structured {@code ToolExecutionRequest}s from inline text
 * (Gemma-4 family), repair malformed tool arguments (DeepSeek-V4-Pro),
 * or otherwise normalize a model's idiosyncratic output into the shape
 * the engines expect.
 *
 * <p>Selection is data-driven: every {@code ModelInfo} carries a
 * {@code messageParser} field; {@link MessageParserRegistry} resolves
 * the name to a bean. Default values come from the model-quirks YAML
 * (pattern-match against the model name) — operators only configure a
 * model explicitly when overriding the bundled defaults.
 *
 * <h2>Implementation contract</h2>
 *
 * <ul>
 *   <li><b>Pure:</b> no I/O, no Spring context calls, no metrics that
 *       require state. The decorator wraps every chat call in the hot
 *       path; allocations should be proportional to the actual
 *       rewriting needed (return the input verbatim when nothing
 *       changed).</li>
 *   <li><b>Defensive:</b> if the response already looks well-formed for
 *       the parser's domain (e.g. it already carries structured
 *       {@code tool_calls}), the parser MUST return the input unchanged.
 *       The cascade may activate a parser for a model that <i>usually</i>
 *       needs it; occasional clean turns must pass through.</li>
 *   <li><b>Stateless:</b> Spring instantiates one bean per parser; many
 *       chat calls run through it concurrently.</li>
 * </ul>
 */
public interface MessageParser {

    /**
     * Stable identifier used by {@code ModelInfo#messageParser()} and
     * the {@code model-quirks.yaml} rule set. Convention: lowercase
     * kebab-case ({@code gemma4}, {@code deepseek-v4}).
     */
    String name();

    /**
     * Transform the provider's raw {@link ChatResponse}. Return
     * {@code raw} unchanged when no rewriting was necessary.
     */
    ChatResponse parse(ChatResponse raw);
}
