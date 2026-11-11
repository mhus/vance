package de.mhus.vance.brain.ai.iris;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

/**
 * {@link HttpClient} decorator that turns Iris' final refusals into
 * {@link IrisRefusedException} on both the plain and the streaming path. Wire
 * bytes are not touched.
 */
final class IrisErrorHttpClient implements HttpClient {

    private final HttpClient delegate;

    IrisErrorHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        try {
            return delegate.execute(request);
        } catch (HttpException e) {
            throw IrisErrors.map(e);
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        delegate.execute(request, parser, new MappingListener(listener));
    }

    /** Forwards everything; only an error is looked at. */
    private static final class MappingListener implements ServerSentEventListener {
        private final ServerSentEventListener target;

        MappingListener(ServerSentEventListener target) {
            this.target = target;
        }

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
            target.onOpen(response);
        }

        @Override
        public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            target.onEvent(event, context);
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            target.onEvent(event);
        }

        @Override
        public void onError(Throwable error) {
            target.onError(IrisErrors.map(error));
        }

        @Override
        public void onClose() {
            target.onClose();
        }
    }
}
