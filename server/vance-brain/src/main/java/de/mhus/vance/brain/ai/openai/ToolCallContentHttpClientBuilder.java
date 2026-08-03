package de.mhus.vance.brain.ai.openai;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import java.time.Duration;

/**
 * {@link HttpClientBuilder} that wraps the classpath-default builder and
 * produces a {@link ToolCallContentHttpClient}, so every request built for
 * the OpenAI provider gets its assistant tool-call {@code content: null}
 * stripped before it hits the wire. See {@link ToolCallContentHttpClient}
 * for the why.
 *
 * <p>Timeout configuration is delegated verbatim to the wrapped builder —
 * langchain4j sets connect/read timeouts on this builder before calling
 * {@link #build()}. A fresh instance is created per model build
 * ({@link #wrappingDefault()}) because langchain4j mutates the builder's
 * timeouts, so instances must not be shared across concurrently built
 * models.
 */
final class ToolCallContentHttpClientBuilder implements HttpClientBuilder {

    private final HttpClientBuilder delegate;

    ToolCallContentHttpClientBuilder(HttpClientBuilder delegate) {
        this.delegate = delegate;
    }

    /** Wrap the single classpath-default builder (the JDK client in this build). */
    static ToolCallContentHttpClientBuilder wrappingDefault() {
        return new ToolCallContentHttpClientBuilder(HttpClientBuilderLoader.loadHttpClientBuilder());
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
        return new ToolCallContentHttpClient(delegate.build());
    }
}
