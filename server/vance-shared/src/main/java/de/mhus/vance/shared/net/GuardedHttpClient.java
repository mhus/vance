package de.mhus.vance.shared.net;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An {@link HttpClient} that runs every request — and every redirect hop — through
 * an egress check before it leaves. Lets a call site keep its plain
 * {@code client.send(...)} code while the guard sits in the client itself
 * ({@link SsrfGuard#guard}).
 *
 * <p>The delegate must be built with {@link HttpClient.Redirect#NEVER}: a client
 * that follows redirects itself would skip the check on every hop. When
 * {@code followRedirects} is set, this wrapper follows {@code GET}/{@code HEAD}
 * redirects (up to {@link SsrfGuard#DEFAULT_MAX_REDIRECTS}) and checks each target;
 * otherwise the 3xx response goes back to the caller. A hop to another origin
 * carries no credential headers ({@link SsrfGuard#redirectRequest}).
 */
final class GuardedHttpClient extends HttpClient {

    private final HttpClient delegate;
    private final boolean followRedirects;
    private final Consumer<URI> check;

    GuardedHttpClient(HttpClient delegate, boolean followRedirects, Consumer<URI> check) {
        if (delegate.followRedirects() != Redirect.NEVER) {
            throw new IllegalArgumentException(
                    "guarded HttpClient needs a delegate built with Redirect.NEVER, got " + delegate.followRedirects());
        }
        this.delegate = delegate;
        this.followRedirects = followRedirects;
        this.check = check;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        check.accept(request.uri());
        HttpResponse<T> response = delegate.send(request, handler);
        int hops = 0;
        HttpRequest current = request;
        while (followRedirects && hops++ < SsrfGuard.DEFAULT_MAX_REDIRECTS) {
            Optional<HttpRequest> next = redirectOf(current, response);
            if (next.isEmpty()) {
                break;
            }
            current = next.get();
            response = delegate.send(current, handler);
        }
        return response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            check.accept(request.uri());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return delegate.sendAsync(request, handler).thenCompose(r -> followAsync(request, r, handler, 0));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        // Pushed resources would arrive from targets nobody checked — not offered.
        return sendAsync(request, handler);
    }

    private <T> CompletableFuture<HttpResponse<T>> followAsync(
            HttpRequest request, HttpResponse<T> response, HttpResponse.BodyHandler<T> handler, int hops) {
        if (!followRedirects || hops >= SsrfGuard.DEFAULT_MAX_REDIRECTS) {
            return CompletableFuture.completedFuture(response);
        }
        Optional<HttpRequest> next;
        try {
            next = redirectOf(request, response);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (next.isEmpty()) {
            return CompletableFuture.completedFuture(response);
        }
        HttpRequest nextRequest = next.get();
        return delegate.sendAsync(nextRequest, handler)
                .thenCompose(r -> followAsync(nextRequest, r, handler, hops + 1));
    }

    /** The checked follow-up request for a redirect response, or empty when there is none to follow. */
    private Optional<HttpRequest> redirectOf(HttpRequest request, HttpResponse<?> response) {
        if (!isRedirect(response.statusCode()) || !isRedirectableMethod(request.method())) {
            return Optional.empty();
        }
        Optional<String> location = response.headers().firstValue("location");
        if (location.isEmpty()) {
            return Optional.empty();
        }
        URI next = response.uri().resolve(location.get());
        check.accept(next);
        return Optional.of(SsrfGuard.redirectRequest(request, next));
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isRedirectableMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return followRedirects ? Redirect.NORMAL : Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public boolean awaitTermination(Duration duration) throws InterruptedException {
        return delegate.awaitTermination(duration);
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public void shutdownNow() {
        delegate.shutdownNow();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
