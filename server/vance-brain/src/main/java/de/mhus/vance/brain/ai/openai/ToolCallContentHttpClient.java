package de.mhus.vance.brain.ai.openai;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link HttpClient} decorator that strips an explicit {@code "content": null}
 * from assistant messages that carry {@code tool_calls} before the request
 * reaches an OpenAI-compatible endpoint.
 *
 * <p><b>Why.</b> langchain4j serialises an {@code AiMessage} that has tool
 * calls but no text as {@code {"role":"assistant","content":null,"tool_calls":[…]}}.
 * OpenAI proper tolerates the explicit {@code null}, but stricter
 * OpenAI-compatible gateways (notably GLM / Zhipu) reject it with
 * {@code 400 body/messages/<N>/content Invalid input}. The reference Pi
 * coding agent omits the field entirely for tool-call-only turns
 * (<a href="">openai-completions.ts</a>: content is only set when assistant
 * text is non-empty). This decorator replicates that: it removes the
 * {@code content} key so the field is <em>absent</em> rather than {@code null}.
 *
 * <p>The rewrite is a no-op for OpenAI proper (content is optional when
 * {@code tool_calls} is present), so it is applied unconditionally on the
 * OpenAI provider rather than gated per endpoint. Bodies that don't parse
 * as a chat-completions request, or that need no change, pass through
 * byte-for-byte.
 */
final class ToolCallContentHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(ToolCallContentHttpClient.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpClient delegate;

    ToolCallContentHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(rewrite(request));
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser,
            ServerSentEventListener listener) {
        delegate.execute(rewrite(request), parser, listener);
    }

    /**
     * Returns a copy of {@code request} with the body normalised, or the
     * original request when nothing changed (or on any parse failure —
     * the fix must never break an otherwise valid request).
     */
    private static HttpRequest rewrite(HttpRequest request) {
        String body = request.body();
        String normalized = normalizeRequestBody(body);
        if (normalized == null) {
            return request;
        }
        return HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(withoutContentLength(request.headers()))
                .body(normalized)
                .build();
    }

    /**
     * Removes {@code "content": null} (and blank-string content) from
     * assistant messages that carry a non-empty {@code tool_calls} array.
     *
     * @return the rewritten JSON body, or {@code null} when the body was
     *     left unchanged (unparseable, not a chat request, or nothing to
     *     strip). {@code null} means "send the original untouched".
     */
    static @Nullable String normalizeRequestBody(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            log.trace("ToolCallContentHttpClient: body not JSON, passing through: {}", e.toString());
            return null;
        }
        if (!(root instanceof ObjectNode obj) || !(obj.get("messages") instanceof ArrayNode messages)) {
            return null;
        }
        boolean changed = false;
        for (JsonNode element : messages) {
            if (!(element instanceof ObjectNode msg)) {
                continue;
            }
            JsonNode role = msg.get("role");
            if (role == null || !"assistant".equals(role.asString())) {
                continue;
            }
            JsonNode toolCalls = msg.get("tool_calls");
            boolean hasToolCalls = toolCalls instanceof ArrayNode arr && !arr.isEmpty();
            if (!hasToolCalls) {
                continue;
            }
            JsonNode content = msg.get("content");
            if (content == null) {
                continue; // field already absent — nothing to do
            }
            boolean emptyContent = content.isNull()
                    || (content.isString() && content.asString().isBlank());
            if (emptyContent) {
                msg.remove("content");
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (RuntimeException e) {
            log.trace("ToolCallContentHttpClient: re-serialize failed, passing through: {}",
                    e.toString());
            return null;
        }
    }

    /**
     * Drops any {@code Content-Length} header (case-insensitive) so the
     * transport recomputes it from the rewritten body — the stripped
     * message shortens the payload.
     */
    private static Map<String, List<String>> withoutContentLength(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>(headers);
        copy.keySet().removeIf(k -> k != null && k.equalsIgnoreCase("Content-Length"));
        return copy;
    }
}
