package de.mhus.vance.brain.ai.iris;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IrisErrorHttpClientTest {

    private static final String QUOTA =
            "{\"error\":{\"code\":\"quota_exhausted\",\"message\":\"the quota is exhausted\"}}";
    private static final HttpRequest REQUEST = HttpRequest.builder()
            .method(HttpMethod.POST)
            .url("http://iris/v1/chat/completions")
            .build();

    /** A delegate that answers the way the test script says. */
    private static final class Scripted implements HttpClient {
        private final RuntimeException syncError;
        private final Throwable streamError;
        private final SuccessfulHttpResponse ok;

        Scripted(RuntimeException syncError, Throwable streamError, SuccessfulHttpResponse ok) {
            this.syncError = syncError;
            this.streamError = streamError;
            this.ok = ok;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            if (syncError != null) {
                throw syncError;
            }
            return ok;
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            if (streamError != null) {
                listener.onError(streamError);
                return;
            }
            listener.onOpen(ok);
            listener.onClose();
        }
    }

    private static SuccessfulHttpResponse response() {
        return SuccessfulHttpResponse.builder().statusCode(200).body("{}").build();
    }

    @Test
    void aFinalRefusalOnThePlainPathBecomesTyped() {
        HttpClient client = new IrisErrorHttpClient(new Scripted(new HttpException(402, QUOTA), null, null));

        assertThatThrownBy(() -> client.execute(REQUEST))
                .isInstanceOf(IrisRefusedException.class)
                .hasMessageContaining("quota_exhausted");
    }

    @Test
    void aFinalRefusalOnTheStreamingPathBecomesTypedToo() {
        HttpClient client = new IrisErrorHttpClient(new Scripted(null, new HttpException(402, QUOTA), null));
        AtomicReference<Throwable> seen = new AtomicReference<>();

        client.execute(REQUEST, null, errorListener(seen));

        assertThat(seen.get()).isInstanceOf(IrisRefusedException.class);
    }

    @Test
    void otherErrorsAreLeftAlone() {
        HttpException transientError = new HttpException(503, "{\"error\":{\"code\":\"no_healthy_target\"}}");
        HttpClient client = new IrisErrorHttpClient(new Scripted(transientError, transientError, null));
        AtomicReference<Throwable> seen = new AtomicReference<>();

        assertThatThrownBy(() -> client.execute(REQUEST)).isSameAs(transientError);
        client.execute(REQUEST, null, errorListener(seen));
        assertThat(seen.get()).isSameAs(transientError);
    }

    @Test
    void successAndTheWholeStreamLifecycleAreForwarded() {
        SuccessfulHttpResponse ok = response();
        HttpClient client = new IrisErrorHttpClient(new Scripted(null, null, ok));
        List<String> events = new ArrayList<>();

        assertThat(client.execute(REQUEST)).isSameAs(ok);
        client.execute(REQUEST, null, new ServerSentEventListener() {
            @Override
            public void onOpen(SuccessfulHttpResponse r) {
                events.add("open");
            }

            @Override
            public void onError(Throwable e) {
                events.add("error");
            }

            @Override
            public void onClose() {
                events.add("close");
            }
        });
        assertThat(events).containsExactly("open", "close");
    }

    private static ServerSentEventListener errorListener(AtomicReference<Throwable> into) {
        return new ServerSentEventListener() {
            @Override
            public void onError(Throwable error) {
                into.set(error);
            }
        };
    }
}
