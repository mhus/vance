package de.mhus.vance.brain.ai.parser;

import de.mhus.vance.shared.metric.MetricService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parser for the Gemini 2.5 family, which intermittently emits tool calls as
 * {@code tool_code} pseudo-code in the assistant <em>text</em> instead of a
 * structured {@code tool_calls} field — even for tools that are properly
 * declared as functions. Observed 2026-07-01: the model wrote
 *
 * <pre>
 * ```tool_code
 * print(workpage_create(path="apps/x/page", blocks=[{"type":"heading","content":"Hi"}]))
 * ```
 * </pre>
 *
 * (or a bare {@code tool_code: workpage_create(...)} line) and then narrated
 * "I have created the page" — but nothing ran. The engine sees no tool call,
 * the validation loop kicks in, and the intended action is silently lost.
 * The arthur prompt already forbids this shape, but Gemini ignores it, so we
 * repair it here — the same role the {@link Gemma4MessageParser} plays for
 * the Gemma family.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>If the response already carries structured tool calls — pass through
 *       (defensive: clean turns must not be rewritten).</li>
 *   <li>Collect {@code tool_code} regions: fenced <code>```tool_code</code>
 *       blocks and bare {@code tool_code:} lines. Only calls inside a region
 *       are synthesized — this avoids grabbing a tool name the model merely
 *       quoted in prose.</li>
 *   <li>In each region, locate {@code <name>(<args>)} (unwrapping a
 *       {@code print(...)} wrapper), balancing parentheses so nested
 *       {@code blocks=[{…}]} bodies survive.</li>
 *   <li>Convert the Python-style keyword-argument body into a JSON object
 *       (top-level {@code key=value} → {@code "key":value}; a body that is
 *       already a single {@code {…}} object is used verbatim), then parse it
 *       through Jackson.</li>
 *   <li>Synthesize a {@link ToolExecutionRequest} per parseable call, in
 *       document order. Any failure → pass through unchanged + a metric so
 *       the failure rate is observable.</li>
 * </ol>
 */
@Component
@Slf4j
public class GeminiToolCodeMessageParser implements MessageParser {

    public static final String NAME = "gemini-tool-code";

    /** A fenced block whose info string starts with {@code tool_code}. */
    private static final Pattern FENCE = Pattern.compile(
            "(?s)```+[ \\t]*tool_code[^\\n]*\\n(.*?)```+");

    /** A bare {@code tool_code:} line (optionally a markdown bullet). */
    private static final Pattern MARKER_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:[-*][ \\t]*)?tool_code:[ \\t]*(.+)$");

    /** A tool-name identifier immediately followed by an opening paren. */
    private static final Pattern CALL_START = Pattern.compile(
            "([a-z_][a-z0-9_]*)[ \\t]*\\(");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final @Nullable MetricService metrics;

    public GeminiToolCodeMessageParser(@Nullable MetricService metrics) {
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
        if (original.hasToolExecutionRequests()) {
            record("clean_tool_calls");
            return raw;
        }
        String text = original.text();
        if (text == null || text.isBlank() || !text.contains("tool_code")) {
            return raw;
        }

        List<ToolExecutionRequest> synthesized = new ArrayList<>();
        for (String region : collectRegions(text)) {
            for (Call call : extractCalls(region)) {
                String jsonArgs = argsToJson(call.args());
                if (jsonArgs == null) {
                    record("body_not_parseable");
                    continue;
                }
                synthesized.add(ToolExecutionRequest.builder()
                        .id("gemini-" + UUID.randomUUID())
                        .name(call.name())
                        .arguments(jsonArgs)
                        .build());
            }
        }
        if (synthesized.isEmpty()) {
            record("no_pattern_match");
            return raw;
        }
        record("synthesized");
        log.debug("GeminiToolCodeMessageParser: synthesized {} tool_call(s): {}",
                synthesized.size(), synthesized.stream().map(ToolExecutionRequest::name).toList());
        return rebuildResponse(raw, AiMessage.from(synthesized));
    }

    /** Text fragments that carry {@code tool_code} calls (fences + marker lines). */
    static List<String> collectRegions(String text) {
        List<String> regions = new ArrayList<>();
        Matcher fence = FENCE.matcher(text);
        while (fence.find()) {
            regions.add(fence.group(1));
        }
        Matcher marker = MARKER_LINE.matcher(text);
        while (marker.find()) {
            regions.add(marker.group(1));
        }
        return regions;
    }

    /** Every {@code name(args)} call in a region, {@code print(...)} unwrapped. */
    static List<Call> extractCalls(String region) {
        List<Call> calls = new ArrayList<>();
        Matcher m = CALL_START.matcher(region);
        int scanFrom = 0;
        while (m.find(scanFrom)) {
            String name = m.group(1);
            int open = m.end() - 1;
            int close = matchParen(region, open);
            if (close < 0) break;
            String body = region.substring(open + 1, close);
            if ("print".equals(name)) {
                // Unwrap: re-scan the inner expression for the real call.
                calls.addAll(extractCalls(body));
            } else {
                calls.add(new Call(name, body));
            }
            scanFrom = close + 1;
        }
        return calls;
    }

    /** Index of the {@code )} matching the {@code (} at {@code openIdx} (string-aware). */
    static int matchParen(String s, int openIdx) {
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == quote) inStr = false;
                continue;
            }
            if (c == '"' || c == '\'') { inStr = true; quote = c; continue; }
            if (c == '(') depth++;
            else if (c == ')') { if (--depth == 0) return i; }
        }
        return -1;
    }

    /**
     * Convert a Python-style call body into a JSON object string. A body that
     * is already a single {@code {…}} object is used verbatim; otherwise
     * top-level {@code key=value} pairs become {@code "key":value}. Returns
     * {@code null} when the result does not round-trip through Jackson.
     */
    static @Nullable String argsToJson(String body) {
        String trimmed = body.strip();
        String candidate;
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            candidate = trimmed;
        } else {
            candidate = "{" + convertKwargs(trimmed) + "}";
        }
        try {
            JsonNode node = JSON.readTree(candidate);
            if (node == null || !node.isObject()) return null;
            return JSON.writeValueAsString(node);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Rewrite top-level {@code ident =} to {@code "ident":}, string/bracket-aware. */
    private static String convertKwargs(String body) {
        StringBuilder out = new StringBuilder(body.length() + 16);
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < body.length()) { out.append(body.charAt(i + 1)); i += 2; continue; }
                if (c == quote) inStr = false;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') { inStr = true; quote = c; out.append(c); i++; continue; }
            if (c == '(' || c == '[' || c == '{') { depth++; out.append(c); i++; continue; }
            if (c == ')' || c == ']' || c == '}') { depth--; out.append(c); i++; continue; }
            if (depth == 0 && Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < body.length() && Character.isJavaIdentifierPart(body.charAt(j))) j++;
                int k = j;
                while (k < body.length() && body.charAt(k) == ' ') k++;
                if (k < body.length() && body.charAt(k) == '='
                        && (k + 1 >= body.length() || body.charAt(k + 1) != '=')) {
                    out.append('"').append(body, i, j).append("\":");
                    i = k + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
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

    /** A single extracted call: tool name + raw Python-style arg body. */
    record Call(String name, String args) {}
}
