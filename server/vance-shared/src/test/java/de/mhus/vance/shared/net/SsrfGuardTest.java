package de.mhus.vance.shared.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the SSRF egress policy (code-review F2). Uses literal IPs and
 * {@code localhost} so no real DNS / network is required.
 */
class SsrfGuardTest {

    @Test
    void rejects_nonHttpSchemes() {
        for (String url : new String[] {
                "file:///etc/passwd", "ftp://host/x", "gopher://host",
                "javascript:alert(1)", "jar:http://host!/x"}) {
            assertThatThrownBy(() -> SsrfGuard.assertAllowed(url))
                    .as(url)
                    .isInstanceOf(SsrfGuard.SsrfException.class);
        }
    }

    @Test
    void rejects_loopback_linkLocal_private_wildcard() {
        for (String url : new String[] {
                "http://127.0.0.1/x",
                "http://127.5.6.7/x",
                "http://[::1]/x",
                "http://169.254.169.254/latest/meta-data/",   // cloud metadata
                "http://0.0.0.0/x",
                "http://10.0.0.5/x",
                "http://172.16.4.4/x",
                "http://192.168.1.1/x",
                "http://[fc00::1]/x",                           // IPv6 unique-local
                "http://[fe80::1]/x"}) {                        // IPv6 link-local
            assertThatThrownBy(() -> SsrfGuard.assertAllowed(url))
                    .as(url)
                    .isInstanceOf(SsrfGuard.SsrfException.class);
        }
    }

    @Test
    void rejects_localhost_hostname() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://localhost:9090/actuator"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    void allows_publicLiteralIp() {
        assertThatCode(() -> SsrfGuard.assertAllowed("https://1.1.1.1/x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SsrfGuard.assertAllowed("http://8.8.8.8/dns"))
                .doesNotThrowAnyException();
    }

    @Test
    void isBlocked_flagsIpv4MappedLoopback() throws UnknownHostException {
        // ::ffff:127.0.0.1 must be treated as loopback, not public.
        InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
        org.junit.jupiter.api.Assertions.assertTrue(SsrfGuard.isBlocked(mapped));
    }

    @Test
    void rejects_malformedUrl() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendGuarded_reChecksHeadRedirectIntoPrivateNetwork() throws Exception {
        // A public URL that 302-redirects a HEAD probe at the cloud
        // metadata endpoint must be blocked on the redirect hop — HEAD is
        // idempotent and therefore followed like GET.
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("rawtypes")
        HttpResponse redirect = mock(HttpResponse.class);
        when(redirect.statusCode()).thenReturn(302);
        when(redirect.uri()).thenReturn(URI.create("https://1.1.1.1/x"));
        when(redirect.headers()).thenReturn(HttpHeaders.of(
                Map.of("location", List.of("http://169.254.169.254/latest/meta-data/")),
                (a, b) -> true));
        when(client.send(any(), any())).thenReturn(redirect);

        HttpRequest head = HttpRequest.newBuilder(URI.create("https://1.1.1.1/x"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        assertThatThrownBy(() -> SsrfGuard.sendGuarded(
                client, head, HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    // ──────────────── capped body handler (OOM guard) ────────────────

    @Test
    void capped_abortsBodyOverLimit() throws Exception {
        HttpResponse.BodySubscriber<byte[]> sub = SsrfGuard
                .capped(HttpResponse.BodyHandlers.ofByteArray(), 10)
                .apply(mock(HttpResponse.ResponseInfo.class));
        java.util.concurrent.Flow.Subscription subscription =
                mock(java.util.concurrent.Flow.Subscription.class);
        sub.onSubscribe(subscription);

        sub.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[20]))); // 20 > 10

        org.mockito.Mockito.verify(subscription).cancel();
        assertThatThrownBy(() -> sub.getBody().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void capped_passesBodyUnderLimit() throws Exception {
        HttpResponse.BodySubscriber<byte[]> sub = SsrfGuard
                .capped(HttpResponse.BodyHandlers.ofByteArray(), 100)
                .apply(mock(HttpResponse.ResponseInfo.class));
        sub.onSubscribe(mock(java.util.concurrent.Flow.Subscription.class));

        sub.onNext(List.of(java.nio.ByteBuffer.wrap("hello".getBytes())));
        sub.onComplete();

        byte[] body = sub.getBody().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(new String(body)).isEqualTo("hello");
    }
}
