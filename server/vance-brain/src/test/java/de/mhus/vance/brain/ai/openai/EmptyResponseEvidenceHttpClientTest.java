package de.mhus.vance.brain.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.EmptyResponseEvidence;
import de.mhus.vance.brain.ai.EmptyResponseEvidenceStore;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EmptyResponseEvidenceHttpClient}: the capture rule
 * (empty stream / empty sync body → capture; content, tool calls, errors →
 * no capture), the buffering bounds, and the verbatim listener forwarding
 * — recording must never change delivery semantics.
 */
class EmptyResponseEvidenceHttpClientTest {

    /** Delegate that just hands the streaming listener to the test. */
    private static final class ScriptedClient implements HttpClient {

        final AtomicReference<ServerSentEventListener> listener = new AtomicReference<>();

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener l) {
            listener.set(l);
        }
    }

    /** Delegate answering every sync call with a scripted body. */
    private static final class SyncClient implements HttpClient {

        private SuccessfulHttpResponse response;

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            return response;
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    private static HttpRequest request() {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://gateway.example/v1/chat/completions")
                .body("{}")
                .build();
    }

    private static SuccessfulHttpResponse response(int status, Map<String, List<String>> headers, String body) {
        return SuccessfulHttpResponse.builder()
                .statusCode(status)
                .headers(headers)
                .body(body)
                .build();
    }

    private EmptyResponseEvidenceStore store;
    private EmptyResponseEvidenceHttpClient client;
    private ScriptedClient scripted;
    private RecordingDownstream downstream;

    @BeforeEach
    void setUp() {
        store = new EmptyResponseEvidenceStore();
        scripted = new ScriptedClient();
        client = new EmptyResponseEvidenceHttpClient(scripted, store, "https://gateway.example/v1", "m-1");
        downstream = new RecordingDownstream();
    }

    /** Fails the test on any unexpected downstream callback state. */
    private static final class RecordingDownstream implements ServerSentEventListener {

        int opens;
        int closes;
        int errors;
        int events;

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
            opens++;
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            events++;
        }

        @Override
        public void onError(Throwable error) {
            errors++;
        }

        @Override
        public void onClose() {
            closes++;
        }
    }

    private void runStream(String... frameData) {
        client.execute(request(), null, downstream);
        ServerSentEventListener l = scripted.listener.get();
        l.onOpen(response(200, Map.of("x-request-id", List.of("req-1")), ""));
        for (String data : frameData) {
            l.onEvent(new ServerSentEvent("data", data));
        }
        l.onClose();
    }

    // ──────────────────── capture rule: stream ────────────────────

    @Test
    void emptyStream_isCapturedWithAllFrames() {
        runStream(
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                "{\"choices\":[{\"delta\":{}}]}",
                "[DONE]");

        List<EmptyResponseEvidence> drained = store.drainRecent("openai:m-1");
        assertThat(drained).hasSize(1);
        EmptyResponseEvidence evidence = drained.get(0);
        assertThat(evidence.kind()).isEqualTo(EmptyResponseEvidence.Kind.STREAM);
        assertThat(evidence.statusCode()).isEqualTo(200);
        assertThat(evidence.headers()).containsExactly("x-request-id: req-1");
        assertThat(evidence.frames())
                .containsExactly(
                        "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                        "{\"choices\":[{\"delta\":{}}]}",
                        "[DONE]");
        assertThat(evidence.transcript()).contains("frame 1:").contains("frame 3: [DONE]");
    }

    @Test
    void reasoningOnlyStream_countsAsEmpty_isCaptured() {
        // The aggregator classifies reasoning-only completions as empty —
        // a separate reasoning_content field never becomes answer text.
        runStream("{\"choices\":[{\"delta\":{\"reasoning_content\":\"pondering…\"}}]}", "[DONE]");

        assertThat(store.drainRecent("openai:m-1")).hasSize(1);
    }

    @Test
    void streamWithContent_isNotCaptured() {
        runStream(
                "{\"choices\":[{\"delta\":{\"content\":\"H\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"i\"}}]}",
                "[DONE]");

        assertThat(store.drainRecent("openai:m-1")).isEmpty();
    }

    @Test
    void streamWithToolCalls_isNotCaptured() {
        runStream(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                        + "\"function\":{\"name\":\"file_read\",\"arguments\":\"{}\"}}]}}]}",
                "[DONE]");

        assertThat(store.drainRecent("openai:m-1")).isEmpty();
    }

    @Test
    void streamClosedWithoutAnyDataEvent_isCaptured() {
        // The emptiest possible stream: open, close, nothing between.
        runStream();

        List<EmptyResponseEvidence> drained = store.drainRecent("openai:m-1");
        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).frames()).isEmpty();
        assertThat(drained.get(0).transcript()).contains("without a single data event");
    }

    @Test
    void erroredStream_isNotCaptured() {
        client.execute(request(), null, downstream);
        ServerSentEventListener l = scripted.listener.get();
        l.onOpen(response(200, Map.of(), ""));
        l.onError(new RuntimeException("connection reset"));
        l.onClose();

        assertThat(store.drainRecent("openai:m-1")).isEmpty();
    }

    // ──────────────────── capture rule: sync ────────────────────

    @Test
    void emptySyncBody_isCapturedWithTheBody() {
        SyncClient sync = new SyncClient();
        sync.response = response(
                200,
                Map.of(),
                "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"\"},\"finish_reason\":\"stop\"}]}");
        EmptyResponseEvidenceHttpClient syncClient =
                new EmptyResponseEvidenceHttpClient(sync, store, "https://gateway.example/v1", "m-1");

        syncClient.execute(request());

        List<EmptyResponseEvidence> drained = store.drainRecent("openai:m-1");
        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).kind()).isEqualTo(EmptyResponseEvidence.Kind.SYNC);
        assertThat(drained.get(0).frames()).hasSize(1);
        assertThat(drained.get(0).transcript()).contains("finish_reason");
    }

    @Test
    void syncBodyWithContent_isNotCaptured() {
        SyncClient sync = new SyncClient();
        sync.response = response(
                200, Map.of(), "{\"choices\":[{\"message\":{\"role\":\"assistant\"," + "\"content\":\"hello\"}}]}");
        new EmptyResponseEvidenceHttpClient(sync, store, "https://gateway.example/v1", "m-1").execute(request());

        assertThat(store.drainRecent("openai:m-1")).isEmpty();
    }

    @Test
    void syncBodyWithToolCalls_isNotCaptured() {
        SyncClient sync = new SyncClient();
        sync.response = response(
                200,
                Map.of(),
                "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":null,\"tool_calls\":[{\"function\":{\"name\":\"file_read\"}}]}}]}");
        new EmptyResponseEvidenceHttpClient(sync, store, "https://gateway.example/v1", "m-1").execute(request());

        assertThat(store.drainRecent("openai:m-1")).isEmpty();
    }

    @Test
    void syncBodyWithoutChoices_isNotCaptured() {
        SyncClient sync = new SyncClient();
        sync.response = response(200, Map.of(), "{\"object\":\"list\",\"data\":[]}");
        new EmptyResponseEvidenceHttpClient(sync, store, "https://gateway.example/v1", "m-1").execute(request());

        assertThat(store.drainRecent("openai:m-1")).isEmpty();
    }

    // ──────────────────── bounds & forwarding ────────────────────

    @Test
    void oversizedStream_keepsBoundedCaptureMarkedTruncated() {
        // 300 contentless frames exceed MAX_FRAMES (256): the capture keeps
        // the cap and is marked truncated instead of growing without bound.
        String[] frames = new String[300];
        java.util.Arrays.fill(frames, "{\"choices\":[{\"delta\":{\"reasoning_content\":\"x\"}}]}");
        runStream(frames);

        List<EmptyResponseEvidence> drained = store.drainRecent("openai:m-1");
        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).frames()).hasSize(EmptyResponseEvidenceHttpClient.MAX_FRAMES);
        assertThat(drained.get(0).truncated()).isTrue();
    }

    @Test
    void everyCallbackIsForwardedToTheDownstreamListener() {
        runStream("{\"choices\":[{\"delta\":{}}]}", "[DONE]");

        assertThat(downstream.opens).isEqualTo(1);
        assertThat(downstream.events).isEqualTo(2);
        assertThat(downstream.closes).isEqualTo(1);
        assertThat(downstream.errors).isZero();
    }
}
