package de.mhus.vance.brain.ai.parser;

import de.mhus.vance.shared.metric.MetricService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parser for the Gemma-4 family hosted via OpenAI-compatible endpoints
 * (LM Studio, llama.cpp HTTP server). These tokenizers emit tool calls
 * as plain assistant text instead of populating the structured
 * {@code tool_calls} field, in a Gemma-internal pseudo-JSON shape
 * with {@code <|"|>} as the string delimiter:
 *
 * <pre>
 * Hallo! Wie kann ich dir heute helfen?
 *
 * arthur_action{message:&lt;|"|&gt;Hallo!…&lt;|"|&gt;,reason:&lt;|"|&gt;…&lt;|"|&gt;,type:&lt;|"|&gt;ANSWER&lt;|"|&gt;}
 * </pre>
 *
 * <p>The Arthur/Eddie/Ford/Vogon engines depend on a structured
 * {@code arthur_action} (or sibling) {@code ToolExecutionRequest} every
 * turn; without that the validation loop kicks in and retries until
 * the budget is exhausted (real failure mode observed 2026-06-27). The
 * parser rewrites the inline text into a real
 * {@link ToolExecutionRequest} so the engine layer sees the same shape
 * as for any structured-output-capable model.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>If the response already carries
 *       {@link AiMessage#hasToolExecutionRequests()} structured tool
 *       calls — pass through unchanged. This is the defensive guard:
 *       on rare clean turns we don't manufacture a duplicate.</li>
 *   <li>Locate the last {@code <name>{…}} occurrence in the text.
 *       Gemma puts the tool call after any chat prelude — taking the
 *       last match avoids confusion with a name the model quoted in
 *       its prose.</li>
 *   <li>Body: replace {@code <|"|>} with standard JSON {@code "} so
 *       the resulting fragment can be parsed by Jackson. Unquoted
 *       JSON object keys ({@code message:}, {@code reason:},
 *       {@code type:}) are wrapped in quotes via a separate pass —
 *       Gemma always emits them bareword.</li>
 *   <li>Parse the result through {@link JsonMapper}. If parse
 *       succeeds and yields an object → synthesize a
 *       {@link ToolExecutionRequest} with a stable random id, drop
 *       the text payload, return the rewritten {@link ChatResponse}.</li>
 *   <li>Any failure → pass through unchanged + bump a metric so the
 *       failure rate is observable.</li>
 * </ol>
 */
@Component
@Slf4j
public class Gemma4MessageParser implements MessageParser {

    public static final String NAME = "gemma4";

    /**
     * Tool-name pattern matched at start-of-line or after a newline,
     * followed directly by {@code &#123;}. Vance tool names use the
     * lowercase JS-ident alphabet — no colons, hyphens, or slashes.
     *
     * <p>{@link #findToolCall} finds the LAST in-text occurrence of
     * this pattern, then consumes the body up to the LAST {@code }}
     * in the response. Taking the last name match avoids the case
     * where a model quotes a tool name mid-prose; taking the last
     * closing brace tolerates nested braces inside a JSON body
     * (Jackson handles balanced-ness on the parse step).
     */
    private static final Pattern TOOL_NAME_PREFIX = Pattern.compile(
            "(?:^|\\n)[ \\t]*([a-z_][a-z0-9_]*)\\{");

    /**
     * Gemma's quote-token leak — replaced by a standard JSON quote
     * before we hand the fragment to Jackson.
     */
    private static final String QUOTE_TOKEN = "<|\"|>";

    /**
     * Wraps a bareword JSON key in quotes. Gemma writes
     * {@code {message: ..., reason: ..., type: ...}} — JSON wants
     * {@code {"message": ..., "reason": ..., "type": ...}}. Matches a
     * {@code {}-or-comma boundary followed by an unquoted identifier
     * followed by a colon.
     */
    private static final Pattern BAREWORD_KEY = Pattern.compile(
            "([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final @Nullable MetricService metrics;

    public Gemma4MessageParser(@Nullable MetricService metrics) {
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ChatResponse parse(ChatResponse raw) {
        if (raw == null || raw.aiMessage() == null) {
            return raw;
        }
        AiMessage original = raw.aiMessage();
        // Defensive: clean turn → already structured → pass-through.
        if (original.hasToolExecutionRequests()) {
            record("clean_tool_calls");
            return raw;
        }
        String text = original.text();
        if (text == null || text.isBlank()) {
            return raw;
        }
        ToolCall match = findToolCall(text);
        if (match == null) {
            record("no_pattern_match");
            return raw;
        }
        String jsonArgs = toJsonObject(match.body);
        if (jsonArgs == null) {
            record("body_not_parseable");
            return raw;
        }
        ToolExecutionRequest synthesized = ToolExecutionRequest.builder()
                .id("gemma4-" + UUID.randomUUID())
                .name(match.name)
                .arguments(jsonArgs)
                .build();
        record("synthesized");
        log.debug("Gemma4MessageParser: synthesized tool_call name='{}' args={} chars",
                match.name, jsonArgs.length());
        AiMessage rebuilt = AiMessage.from(List.of(synthesized));
        return rebuildResponse(raw, rebuilt);
    }

    /**
     * Locate the last {@code <name>&#123;…&#125;} call in the text.
     * Returns {@code null} if no name prefix matches at a line
     * boundary, or no closing {@code &#125;} follows.
     */
    static @Nullable ToolCall findToolCall(String text) {
        java.util.regex.Matcher m = TOOL_NAME_PREFIX.matcher(text);
        int nameStart = -1;
        int bodyStart = -1;
        String name = null;
        while (m.find()) {
            name = m.group(1);
            nameStart = m.start(1);
            bodyStart = m.end();   // immediately after the '{'
        }
        if (name == null) return null;
        int lastBrace = text.lastIndexOf('}');
        if (lastBrace <= bodyStart) return null;
        String body = text.substring(bodyStart, lastBrace);
        return new ToolCall(name, nameStart, body);
    }

    /** Locator record returned by {@link #findToolCall}. */
    record ToolCall(String name, int nameStartIndex, String body) {}

    /**
     * Convert a Gemma-style body ({@code message:<|"|>...<|"|>,...})
     * into a JSON object string. Returns {@code null} when the body
     * doesn't round-trip cleanly through Jackson — defensive fall-back
     * for any unexpected escape variant.
     */
    private static @Nullable String toJsonObject(String body) {
        String dequoted = body.replace(QUOTE_TOKEN, "\"");
        // Wrap braces around the body BEFORE quoting bareword keys —
        // the regex anchors the first key on the opening '{' and
        // every subsequent key on a comma, which the wrapped form
        // satisfies for every key (including the first).
        String wrapped = "{" + dequoted + "}";
        String keyed = BAREWORD_KEY.matcher(wrapped)
                .replaceAll("$1\"$2\":");
        try {
            JsonNode node = JSON.readTree(keyed);
            if (node == null || !node.isObject()) return null;
            return JSON.writeValueAsString(node);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ChatResponse rebuildResponse(ChatResponse raw, AiMessage rebuilt) {
        ChatResponse.Builder b = ChatResponse.builder().aiMessage(rebuilt);
        if (raw.metadata() != null) {
            b.metadata(raw.metadata());
        } else {
            if (raw.tokenUsage() != null) b.tokenUsage(raw.tokenUsage());
            if (raw.finishReason() != null) b.finishReason(raw.finishReason());
        }
        return b.build();
    }

    private void record(String outcome) {
        if (metrics == null) return;
        metrics.counter("vance.llm.message_parser",
                        "parser", NAME, "outcome", outcome)
                .increment();
    }
}
