package de.mhus.vance.brain.ai.iris;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import java.time.Duration;

/**
 * Wraps another {@link HttpClientBuilder} so that the client it builds is an
 * {@link IrisErrorHttpClient}. Timeouts are delegated verbatim — langchain4j
 * sets them on this builder before calling {@link #build()} — so a fresh
 * instance per model build is required.
 */
final class IrisErrorHttpClientBuilder implements HttpClientBuilder {

    private final HttpClientBuilder delegate;

    IrisErrorHttpClientBuilder(HttpClientBuilder delegate) {
        this.delegate = delegate;
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        delegate.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        delegate.readTimeout(timeout);
        return this;
    }

    @Override
    public HttpClient build() {
        return new IrisErrorHttpClient(delegate.build());
    }
}
