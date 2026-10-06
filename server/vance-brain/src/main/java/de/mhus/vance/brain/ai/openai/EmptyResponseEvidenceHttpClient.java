package de.mhus.vance.brain.ai.openai;

import de.mhus.vance.brain.ai.EmptyResponseEvidence;
import de.mhus.vance.brain.ai.EmptyResponseEvidenceStore;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link HttpClient} decorator that records the raw wire transcript of a
 * response which arrives <em>empty</em> — no text content, no tool call —
 * into the {@link EmptyResponseEvidenceStore}, for the empty-response
 * post-mortem ({@link de.mhus.vance.brain.ai.EmptyResponseDiagnosticSink}).
 *
 * <p><b>Why raw frames.</b> The resilience layer diagnoses empty
 * completions from the aggregated result: an empty {@code AiMessage},
 * finish reason, token usage. What it never sees is what the endpoint
 * actually put on the wire — the single empty token, the mangled
 * tool-call arguments a gateway dropped, a cache stub, a content filter
 * folded into {@code finish_reason:"stop"}. All of that lives only in the
 * SSE frames, and only until the next response. This decorator keeps the
 * frames of exactly the responses worth investigating.
 *
 * <p><b>Capture rule.</b> A response is worth capturing when the wire
 * itself looked empty: no SSE frame carried a content delta with text and
 * no frame mentioned {@code tool_calls} (sync: the message has neither
 * content nor tool calls). The rule mirrors the aggregator's empty
 * definition — reasoning-only responses (a separate {@code
 * reasoning_content} field, no content) count as empty, because that is
 * how the caller sees them too. One deliberate divergence:
 * whitespace-only content flips the flag and goes uncaptured, because
 * telling blank from empty would need a per-frame JSON parse on the hot
 * streaming path. Such a response still reports without wire evidence —
 * nothing is lost but the transcript.
 *
 * <p>Healthy responses flip the flag at the first content/tool-call
 * frame, release the buffer immediately and leave no capture — the
 * recorder must not tax the hot streaming path. Buffering caps at
 * {@link #MAX_FRAMES} frames / {@link #MAX_TOTAL_CHARS} characters; a
 * capture that hits either keeps what it has and is marked truncated.
 * Errors are never captured — they carry their own message and travel
 * through the regular error path.
 *
 * <p>Recording is unconditional, not gated on "does this chat have a
 * diagnostic sink": sync models serve sink-less call sites (LightLlm,
 * compaction) whose empties are just as interesting, the store is bounded
 * either way, and gating would need sink plumbing through the provider
 * stack for no memory win.
 */
final class EmptyResponseEvidenceHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(EmptyResponseEvidenceHttpClient.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * Upper bound on buffered frames per response. An empty response has a
     * handful; the cap exists so a pathological stream (frames that never
     * carry content — reasoning deltas) cannot grow the buffer unbounded.
     */
    static final int MAX_FRAMES = 256;

    /** Upper bound on total buffered characters across all frames. */
    static final int MAX_TOTAL_CHARS = 64 * 1024;

    /** Upper bound for a single frame's stored form. */
    static final int MAX_FRAME_CHARS = 4096;

    /**
     * Response headers that never enter a diagnostic transcript. Keys are
     * lower-case; header maps on the JDK client are case-insensitive but
     * foreign gateways have shipped surprises.
     */
    private static final Set<String> REDACTED_HEADERS =
            Set.of("authorization", "cookie", "set-cookie", "proxy-authorization", "proxy-authenticate");

    /**
     * A content field with at least one character of payload — the empty
     * forms ({@code "content":""}, {@code "content":null}) match nothing.
     * Escaped openers ({@code "content":"\""}) match, which is the point:
     * the model said <i>something</i>. Substring heuristics instead of a
     * per-frame JSON parse, because frames arrive at token rate on the
     * hot streaming path.
     */
    private static final Pattern CONTENT_WITH_TEXT = Pattern.compile("\"content\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])");

    private static final String TOOL_CALLS_KEY = "\"tool_calls\"";

    private final HttpClient delegate;
    private final EmptyResponseEvidenceStore store;
    private final String baseUrl;
    private final String modelName;

    EmptyResponseEvidenceHttpClient(
            HttpClient delegate, EmptyResponseEvidenceStore store, String baseUrl, String modelName) {
        this.delegate = delegate;
        this.store = store;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        SuccessfulHttpResponse response = delegate.execute(request);
        captureSyncIfEmpty(response);
        return response;
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        delegate.execute(request, parser, new RecordingListener(listener));
    }

    /** Captures a sync response whose message carries neither text nor a tool call. */
    private void captureSyncIfEmpty(SuccessfulHttpResponse response) {
        try {
            String body = response.body();
            if (body == null || !body.contains("\"choices\"")) {
                return; // not a chat-completion body — nothing to diagnose
            }
            // Cheap pre-checks first: a body that visibly carries content
            // or tool calls never pays the JSON parse. Sync bodies are
            // full responses, so parsing is per-call, not per-token — but
            // most calls are healthy, and they should cost almost nothing.
            if (body.contains(TOOL_CALLS_KEY) || CONTENT_WITH_TEXT.matcher(body).find()) {
                return;
            }
            if (!syncIsEmpty(body)) {
                return;
            }
            store.register(new EmptyResponseEvidence(
                    Instant.now(),
                    EmptyResponseEvidence.Kind.SYNC,
                    baseUrl,
                    modelName,
                    response.statusCode(),
                    safeHeaders(response.headers()),
                    List.of(truncateFrame(body)),
                    body.length() > MAX_FRAME_CHARS));
        } catch (RuntimeException e) {
            log.debug("Empty-response capture (sync) failed: {}", e.toString());
        }
    }

    /**
     * Parsed view of a sync body: is it a chat completion carrying neither
     * text nor a tool call? Non-chat JSON and unparseable bodies are "not
     * empty" — capture only what is clearly a blank completion.
     */
    private static boolean syncIsEmpty(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray()) {
                return false; // JSON, but not a chat completion
            }
            if (choices.isEmpty()) {
                return true; // "no choice at all" is the emptiest response there is
            }
            JsonNode message = choices.get(0).path("message");
            JsonNode content = message.get("content");
            boolean hasText =
                    content != null && content.isString() && !content.asString().isBlank();
            JsonNode toolCalls = message.get("tool_calls");
            boolean hasToolCalls = toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();
            return !hasText && !hasToolCalls;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** One frame's stored form, capped at {@link #MAX_FRAME_CHARS}. */
    private static String truncateFrame(String data) {
        return data.length() <= MAX_FRAME_CHARS ? data : data.substring(0, MAX_FRAME_CHARS) + " …(truncated)";
    }

    /** Response headers as sorted {@code "Name: value"} lines, credentials redacted. */
    private static List<String> safeHeaders(Map<String, List<String>> headers) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || entry.getValue().isEmpty()) {
                continue;
            }
            if (REDACTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(name + ": " + String.join(", ", entry.getValue()));
        }
        out.sort(String::compareTo);
        return out;
    }

    /** True when an SSE frame carries content with text or a tool call. */
    static boolean frameLooksNonEmpty(String data) {
        if (data.contains(TOOL_CALLS_KEY)) {
            return true;
        }
        return CONTENT_WITH_TEXT.matcher(data).find();
    }

    /**
     * Listener wrapper: buffers frames while the stream looks empty, stops
     * at the first sign of content, registers on close if it never came.
     * Forwards every callback verbatim — recording must never change
     * delivery semantics.
     */
    private final class RecordingListener implements ServerSentEventListener {

        private final ServerSentEventListener downstream;
        private final List<String> frames = new ArrayList<>();
        private int bufferedChars;
        private boolean sawContentOrToolCall;
        private boolean truncated;
        private boolean failed;
        private @Nullable SuccessfulHttpResponse opened;

        RecordingListener(ServerSentEventListener downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
            opened = response;
            downstream.onOpen(response);
        }

        @Override
        public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            record(event);
            downstream.onEvent(event, context);
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            record(event);
            downstream.onEvent(event);
        }

        @Override
        public void onError(Throwable error) {
            // An error carries its own evidence (message, status) and its
            // own reporting path — capture only successful-looking blanks.
            failed = true;
            frames.clear();
            downstream.onError(error);
        }

        @Override
        public void onClose() {
            try {
                if (!sawContentOrToolCall && !failed) {
                    store.register(new EmptyResponseEvidence(
                            Instant.now(),
                            EmptyResponseEvidence.Kind.STREAM,
                            baseUrl,
                            modelName,
                            opened != null ? opened.statusCode() : 0,
                            opened != null ? safeHeaders(opened.headers()) : List.of(),
                            List.copyOf(frames),
                            truncated));
                }
            } catch (RuntimeException e) {
                log.debug("Empty-response capture (stream) failed: {}", e.toString());
            }
            downstream.onClose();
        }

        private void record(ServerSentEvent event) {
            String data = event.data();
            if (data == null) {
                return; // comment/event-name-only frames carry no payload
            }
            if (!sawContentOrToolCall && frameLooksNonEmpty(data)) {
                sawContentOrToolCall = true;
                frames.clear(); // healthy response — release the buffer immediately
                return;
            }
            if (sawContentOrToolCall) {
                return; // known healthy — no further cost
            }
            if (frames.size() >= MAX_FRAMES || bufferedChars + data.length() > MAX_TOTAL_CHARS) {
                truncated = true;
                return;
            }
            String stored = truncateFrame(data);
            if (stored.length() != data.length()) {
                truncated = true;
            }
            frames.add(stored);
            bufferedChars += stored.length();
        }
    }
}
