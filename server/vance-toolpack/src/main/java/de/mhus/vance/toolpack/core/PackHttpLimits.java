package de.mhus.vance.toolpack.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Response-size guards for the tool-pack HTTP consumers (REST invoker + MCP
 * HTTP transport). toolpack keeps a minimal dependency set (vance-api only, no
 * vance-shared) and its own {@code PackHttpClient}, so it can't reuse the
 * shared {@code SsrfGuard.capped(...)} — this is the local equivalent.
 *
 * <p>A configured REST/MCP endpoint that streams a multi-GB body would
 * otherwise buffer fully into the Brain heap and OOM the JVM for all tenants.
 * Both helpers abort with {@link IOException} once the cap is exceeded.
 */
public final class PackHttpLimits {

    private PackHttpLimits() {}

    /** Default per-response body cap: 32 MiB (matches the shared SsrfGuard cap). */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 32L * 1024 * 1024;

    /**
     * A {@link HttpResponse.BodyHandler} that forwards to {@code
     * BodyHandlers.ofString()} but fails the body once {@code maxBytes} is
     * exceeded — a drop-in replacement for {@code ofString()} on the REST path.
     */
    public static HttpResponse.BodyHandler<String> cappedString(long maxBytes) {
        return info -> new LimitingSubscriber<>(
                HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8),
                maxBytes);
    }

    /** Read {@code in} fully into a byte[] but abort if it exceeds {@code maxBytes}. */
    public static byte[] readCapped(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("response body exceeds " + maxBytes + " bytes");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /** Byte-counting pass-through subscriber; cancels + errors once over the cap. */
    private static final class LimitingSubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> downstream;
        private final long maxBytes;
        private final AtomicLong received = new AtomicLong();
        private Flow.Subscription subscription;
        private boolean terminated;

        LimitingSubscriber(HttpResponse.BodySubscriber<T> downstream, long maxBytes) {
            this.downstream = downstream;
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<T> getBody() {
            return downstream.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription sub) {
            this.subscription = sub;
            downstream.onSubscribe(sub);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (terminated) {
                return;
            }
            long total = 0;
            for (ByteBuffer b : item) {
                total += b.remaining();
            }
            if (received.addAndGet(total) > maxBytes) {
                terminated = true;
                if (subscription != null) {
                    subscription.cancel();
                }
                downstream.onError(new IOException(
                        "response body exceeds " + maxBytes + " bytes"));
                return;
            }
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!terminated) {
                downstream.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!terminated) {
                downstream.onComplete();
            }
        }
    }
}
