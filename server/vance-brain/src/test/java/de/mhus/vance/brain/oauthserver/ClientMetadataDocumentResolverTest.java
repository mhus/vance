package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientMetadataDocumentResolverTest {

    private static final String URL = "https://app.example.com/oauth/client.json";

    private static final OAuthServerSettings SETTINGS =
            new OAuthServerSettings("https://vance.example.com", true, 3600, 30, 365, true, true);

    /** Resolver with fixed DNS and body — no network. */
    private static class Stub extends ClientMetadataDocumentResolver {
        private final String ip;
        private final String body;
        final AtomicInteger fetches = new AtomicInteger();

        Stub(String ip, String body) {
            super(SETTINGS);
            this.ip = ip;
            this.body = body;
        }

        @Override
        InetAddress[] resolveHost(String host) throws UnknownHostException {
            return new InetAddress[] {InetAddress.getByName(ip)};
        }

        @Override
        String fetch(URI uri) {
            fetches.incrementAndGet();
            return body;
        }
    }

    private static String doc(String clientId, String redirect) {
        return """
            {"client_id":"%s","client_name":"Example","redirect_uris":["%s"],
             "token_endpoint_auth_method":"none"}
            """.formatted(clientId, redirect);
    }

    @Test
    void resolve_validDocument_isCachedAndKindCimd() {
        Stub stub = new Stub("93.184.215.14", doc(URL, "http://127.0.0.1:3000/callback"));

        ResolvedOAuthClient client = stub.resolve(URL);
        stub.resolve(URL);

        assertThat(client.kind()).isEqualTo(OAuthClientKind.CIMD);
        assertThat(client.clientName()).isEqualTo("Example");
        assertThat(client.allowsRedirect("http://127.0.0.1:41000/callback")).isTrue();
        assertThat(stub.fetches).hasValue(1);
    }

    @Test
    void resolve_documentNamingAnotherUrl_isRejected() {
        Stub stub = new Stub("93.184.215.14", doc("https://other.example/c.json", "https://app.example.com/cb"));

        assertThatThrownBy(() -> stub.resolve(URL))
                .isInstanceOf(InvalidClientException.class)
                .hasMessageContaining("client_id");
    }

    @Test
    void resolve_hostOnAPrivateAddress_isRefusedBeforeFetching() {
        Stub stub = new Stub("10.42.40.10", doc(URL, "https://app.example.com/cb"));

        assertThatThrownBy(() -> stub.resolve(URL)).isInstanceOf(InvalidClientException.class);
        assertThat(stub.fetches).hasValue(0);
    }

    @Test
    void resolve_unacceptableRedirect_isRejected() {
        Stub stub = new Stub("93.184.215.14", doc(URL, "http://evil.example/cb"));

        assertThatThrownBy(() -> stub.resolve(URL)).isInstanceOf(InvalidClientException.class);
    }

    @Test
    void checkUrl_requiresHttpsDefaultPortAndPath() {
        assertThatThrownBy(() -> ClientMetadataDocumentResolver.checkUrl("http://app.example.com/c.json"))
                .isInstanceOf(InvalidClientException.class);
        assertThatThrownBy(() -> ClientMetadataDocumentResolver.checkUrl("https://app.example.com:8443/c.json"))
                .isInstanceOf(InvalidClientException.class);
        assertThatThrownBy(() -> ClientMetadataDocumentResolver.checkUrl("https://app.example.com/"))
                .isInstanceOf(InvalidClientException.class);
        assertThat(ClientMetadataDocumentResolver.checkUrl(URL).getHost()).isEqualTo("app.example.com");
    }

    @Test
    void isPublic_allowsGlobalUnicast() {
        for (String global : new String[] {
            "93.184.215.14",
            "8.8.8.8",
            "1.1.1.1",
            "100.63.255.255", // just below CGNAT
            "100.128.0.1", // just above CGNAT
            "172.32.0.1", // just above 172.16/12
            "198.17.255.255", // just below 198.18/15
            "198.20.0.1", // just above 198.18/15
            "223.255.255.254", // just below multicast
            "2606:4700:4700::1111",
            "2a00:1450:4001:80b::200e",
            "2001:200::1", // just above 2001::/23
        }) {
            assertThat(ClientMetadataDocumentResolver.isPublic(InetAddress.ofLiteral(global)))
                    .as(global)
                    .isTrue();
        }
    }

    @Test
    void isPublic_excludesEverySpecialPurposeRange() {
        for (String internal : new String[] {
            // IPv4
            "0.0.0.0", // unspecified
            "0.1.2.3", // this network 0/8
            "10.0.0.1", // private
            "10.42.40.10", // the cluster
            "100.64.0.1", // CGNAT
            "100.127.255.254", // CGNAT upper edge
            "127.0.0.1", // loopback
            "127.255.255.254",
            "169.254.169.254", // link-local / cloud metadata
            "172.16.0.1", // private
            "172.31.255.254",
            "192.0.0.1", // IETF protocol assignments 192.0.0.0/24
            "192.0.0.170", // NAT64 discovery
            "192.0.2.1", // TEST-NET-1
            "192.88.99.1", // 6to4 relay anycast
            "192.168.1.1", // private
            "198.18.0.1", // benchmarking
            "198.19.255.254",
            "198.51.100.1", // TEST-NET-2
            "203.0.113.1", // TEST-NET-3
            "224.0.0.1", // multicast
            "239.255.255.250",
            "240.0.0.1", // reserved 240/4
            "255.255.255.255", // broadcast
            // IPv6
            "::", // unspecified
            "::1", // loopback
            "::a00:1", // IPv4-compatible ::10.0.0.1
            "::5db8:d70e", // IPv4-compatible of a public v4 — still refused
            "::ffff:10.0.0.1", // IPv4-mapped
            "64:ff9b::a00:1", // NAT64 well-known prefix
            "64:ff9b::5db8:d70e", // NAT64 of a public v4 — refused, translator is ours
            "64:ff9b:1::a00:1", // NAT64 local-use 64:ff9b:1::/48
            "100::1", // discard-only
            "2001::1", // Teredo 2001::/32
            "2001:0:4136:e378:8000:63bf:3fff:fdd2", // Teredo
            "2001:10::1", // ORCHID
            "2001:db8::1", // documentation
            "2002:a00:1::1", // 6to4 of 10.0.0.1
            "2002:5db8:d70e::1", // 6to4 of a public v4
            "3fff::1", // documentation 3fff::/20
            "fc00::1", // unique local
            "fd00::1",
            "fe80::1", // link-local
            "fec0::1", // site-local (deprecated)
            "ff02::1", // multicast
        }) {
            assertThat(ClientMetadataDocumentResolver.isPublic(InetAddress.ofLiteral(internal)))
                    .as(internal)
                    .isFalse();
        }
    }

    @Test
    void resolve_retrievalFailures_shareOneGenericMessage() {
        Stub privateHost = new Stub("10.42.40.10", doc(URL, "https://app.example.com/cb"));
        Stub unresolvable = new Stub("93.184.215.14", doc(URL, "https://app.example.com/cb")) {
            @Override
            InetAddress[] resolveHost(String host) throws UnknownHostException {
                throw new UnknownHostException(host);
            }
        };

        assertThatThrownBy(() -> privateHost.resolve(URL))
                .isInstanceOf(InvalidClientException.class)
                .hasMessage(ClientMetadataDocumentResolver.RETRIEVAL_FAILED);
        assertThatThrownBy(() -> unresolvable.resolve(URL))
                .isInstanceOf(InvalidClientException.class)
                .hasMessage(ClientMetadataDocumentResolver.RETRIEVAL_FAILED);
    }

    @Test
    void resolve_failure_isCachedNegatively() {
        Stub stub = new Stub("93.184.215.14", "not json");

        assertThatThrownBy(() -> stub.resolve(URL)).isInstanceOf(InvalidClientException.class);
        assertThatThrownBy(() -> stub.resolve(URL))
                .isInstanceOf(InvalidClientException.class)
                .hasMessageContaining("JSON");
        assertThat(stub.fetches).hasValue(1);
    }

    @Test
    void resolve_redirectOnTheServersOwnOrigin_isRejected() {
        Stub stub = new Stub("93.184.215.14", doc(URL, "https://vance.example.com/login.html"));

        assertThatThrownBy(() -> stub.resolve(URL))
                .isInstanceOf(InvalidClientException.class)
                .hasMessageContaining("redirect URI");
    }

    @Test
    void resolve_clientNameIsCleanedOfInvisibleCharacters() {
        Stub stub = new Stub("93.184.215.14", """
            {"client_id":"%s","client_name":"Vance\u202Etope\u200B Tool","redirect_uris":["https://app.example.com/cb"]}
            """.formatted(URL));

        assertThat(stub.resolve(URL).clientName()).isEqualTo("Vancetope Tool");
    }

    @Test
    void resolve_whenAllFetchSlotsAreBusy_refusesImmediately() throws Exception {
        CountDownLatch inFetch = new CountDownLatch(ClientMetadataDocumentResolver.MAX_CONCURRENT_FETCHES);
        CountDownLatch release = new CountDownLatch(1);
        Stub stub = new Stub("93.184.215.14", "{}") {
            @Override
            String fetch(URI uri) {
                inFetch.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "{}";
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(ClientMetadataDocumentResolver.MAX_CONCURRENT_FETCHES);
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < ClientMetadataDocumentResolver.MAX_CONCURRENT_FETCHES; i++) {
                String url = "https://app.example.com/c" + i + ".json";
                running.add(pool.submit(() -> {
                    try {
                        stub.resolve(url);
                    } catch (InvalidClientException expected) {
                        // the "{}" document is invalid — only the slot matters here
                    }
                }));
            }
            assertThat(inFetch.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> stub.resolve(URL))
                    .isInstanceOf(InvalidClientException.class)
                    .hasMessageContaining("busy");

            release.countDown();
            for (Future<?> f : running) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void fetch_nonOkStatus_isAGenericFailure() {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);
        doReturn(CompletableFuture.completedFuture(response)).when(http).sendAsync(any(), any());
        ClientMetadataDocumentResolver resolver =
                new ClientMetadataDocumentResolver(SETTINGS, http, Ticker.systemTicker());

        assertThatThrownBy(() -> resolver.fetch(URI.create(URL)))
                .isInstanceOf(InvalidClientException.class)
                .hasMessage(ClientMetadataDocumentResolver.RETRIEVAL_FAILED);
    }

    @Test
    void fetch_failedExchange_isAGenericFailure() {
        HttpClient http = mock(HttpClient.class);
        doReturn(CompletableFuture.failedFuture(new IOException("connection reset")))
                .when(http)
                .sendAsync(any(), any());
        ClientMetadataDocumentResolver resolver =
                new ClientMetadataDocumentResolver(SETTINGS, http, Ticker.systemTicker());

        assertThatThrownBy(() -> resolver.fetch(URI.create(URL)))
                .isInstanceOf(InvalidClientException.class)
                .hasMessage(ClientMetadataDocumentResolver.RETRIEVAL_FAILED);
    }

    @Test
    void limitedBodySubscriber_collectsUpToTheLimit() throws Exception {
        ClientMetadataDocumentResolver.LimitedBodySubscriber subscriber =
                new ClientMetadataDocumentResolver.LimitedBodySubscriber(10);
        Flow.Subscription subscription = mock(Flow.Subscription.class);

        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap("hello".getBytes()), ByteBuffer.wrap("world".getBytes())));
        subscriber.onComplete();

        assertThat(new String(subscriber.getBody().toCompletableFuture().get())).isEqualTo("helloworld");
    }

    @Test
    void limitedBodySubscriber_cancelsOnTheFirstByteTooMany() {
        ClientMetadataDocumentResolver.LimitedBodySubscriber subscriber =
                new ClientMetadataDocumentResolver.LimitedBodySubscriber(10);
        Flow.Subscription subscription = mock(Flow.Subscription.class);

        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap("hello".getBytes())));
        subscriber.onNext(List.of(ByteBuffer.wrap("world!".getBytes())));

        verify(subscription).cancel();
        assertThat(subscriber.getBody().toCompletableFuture())
                .isCompletedExceptionally()
                .failsWithin(java.time.Duration.ZERO)
                .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                .withCauseInstanceOf(ClientMetadataDocumentResolver.BodyTooLargeException.class);
    }

    @Test
    void limitedBodySubscriber_withZeroLimit_neverReadsTheBody() throws Exception {
        ClientMetadataDocumentResolver.LimitedBodySubscriber subscriber =
                new ClientMetadataDocumentResolver.LimitedBodySubscriber(0);
        Flow.Subscription subscription = mock(Flow.Subscription.class);

        subscriber.onSubscribe(subscription);

        verify(subscription).cancel();
        assertThat(subscriber.getBody().toCompletableFuture().get()).isEmpty();
    }
}
