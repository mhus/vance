package de.mhus.vance.brain.ai.openai;

import de.mhus.vance.brain.ai.EmptyResponseEvidenceStore;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>Optionally also chains the empty-response wire recorder in front
 * (see {@link EmptyResponseEvidenceHttpClient}): when
 * {@link #evidenceRecording} is set, {@link #build()} wraps the
 * tool-call-content client in the recorder so blank completions leave a
 * raw frame capture for the empty-response post-mortem.
 */
public final class ToolCallContentHttpClientBuilder implements HttpClientBuilder {
    private final HttpClientBuilder delegate;
    private @Nullable EvidenceSpec evidence;

    ToolCallContentHttpClientBuilder(HttpClientBuilder delegate) {
        this.delegate = delegate;
    }

    /**
     * Enables the empty-response wire recorder for the client about to be
     * built. Optional — absence keeps the plain tool-call-content chain,
     * which keeps unit tests of either concern independent of the other.
     */
    public ToolCallContentHttpClientBuilder evidenceRecording(EvidenceSpec spec) {
        this.evidence = spec;
        return this;
    }

    /**
     * What the recorder needs at capture time: where to file captures,
     * and which endpoint/model they belong to. The model name comes from
     * the build config rather than the wire request — the client is built
     * per chat, so both are already in scope and no per-request JSON
     * extraction is needed.
     */
    public record EvidenceSpec(EmptyResponseEvidenceStore store, String baseUrl, String modelName) {}

    /** Wrap the single classpath-default builder (the JDK client in this build). */
    public static ToolCallContentHttpClientBuilder wrappingDefault() {
        return new ToolCallContentHttpClientBuilder(HttpClientBuilderLoader.loadHttpClientBuilder());
    }

    /**
     * Wrap the given builder — the extension point for a custom TLS policy
     * ({@code TlsInsecure} instances hand in a trust-all JDK builder).
     */
    public static ToolCallContentHttpClientBuilder wrapping(HttpClientBuilder delegate) {
        return new ToolCallContentHttpClientBuilder(delegate);
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
        HttpClient client = new ToolCallContentHttpClient(delegate.build());
        EvidenceSpec spec = evidence;
        return spec == null
                ? client
                : new EmptyResponseEvidenceHttpClient(client, spec.store(), spec.baseUrl(), spec.modelName());
    }
}
