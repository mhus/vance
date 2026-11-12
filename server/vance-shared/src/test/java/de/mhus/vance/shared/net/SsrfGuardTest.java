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

    @org.junit.jupiter.api.AfterEach
    void resetEscapeHatch() {
        // The dev/test escape hatch is a static global — never leak it into
        // other tests (which assume the secure default-deny).
        SsrfGuard.setAllowPrivate(false);
        SsrfGuard.setDenyList(EgressDenyList.empty());
        SsrfGuard.setPrivateNetworks(SsrfGuard.PrivateNetworks.DENY);
        SsrfGuard.setProviderHosts(List.of());
    }

    @Test
    void providerHosts_letAProviderEndpointReachAPrivateGateway_butNotAFetchingTool() {
        SsrfGuard.setProviderHosts(List.of("Localhost"));

        assertThatCode(() -> SsrfGuard.assertProviderEndpointAllowed("http://localhost:8080/v1"))
                .doesNotThrowAnyException();
        // The fetching tools keep the full policy for the very same host.
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://localhost:8080/v1"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
        // A host that is not named stays refused, also for a provider endpoint.
        assertThatThrownBy(() -> SsrfGuard.assertProviderEndpointAllowed("http://127.0.0.1:8080/v1"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    void allowPrivate_escapeHatch_permitsLoopbackAndPrivate_thenResets() {
        // Operator opt-in (vance.net.ssrf.allow-private) for local testing: the
        // guard stops blocking loopback / LAN targets so a dev can reach them.
        SsrfGuard.setAllowPrivate(true);
        assertThatCode(() -> SsrfGuard.assertAllowed("http://localhost:9090/actuator"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SsrfGuard.assertAllowed("http://127.0.0.1:8080/x")).doesNotThrowAnyException();
        assertThatCode(() -> SsrfGuard.assertAllowed("http://192.168.1.10/x")).doesNotThrowAnyException();

        // Turning it back off restores the block (matches the prod default).
        SsrfGuard.setAllowPrivate(false);
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://localhost:9090/actuator"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    void allowPrivate_stillRejectsNonHttpScheme() {
        // The escape hatch relaxes only the address policy, not the scheme gate.
        SsrfGuard.setAllowPrivate(true);
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("file:///etc/passwd"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    void rejects_nonHttpSchemes() {
        for (String url : new String[] {
            "file:///etc/passwd", "ftp://host/x", "gopher://host", "javascript:alert(1)", "jar:http://host!/x"
        }) {
            assertThatThrownBy(() -> SsrfGuard.assertAllowed(url)).as(url).isInstanceOf(SsrfGuard.SsrfException.class);
        }
    }

    @Test
    void rejects_loopback_linkLocal_private_wildcard() {
        for (String url : new String[] {
            "http://127.0.0.1/x",
            "http://127.5.6.7/x",
            "http://[::1]/x",
            "http://169.254.169.254/latest/meta-data/", // cloud metadata
            "http://0.0.0.0/x",
            "http://10.0.0.5/x",
            "http://172.16.4.4/x",
            "http://192.168.1.1/x",
            "http://[fc00::1]/x", // IPv6 unique-local
            "http://[fe80::1]/x"
        }) { // IPv6 link-local
            assertThatThrownBy(() -> SsrfGuard.assertAllowed(url)).as(url).isInstanceOf(SsrfGuard.SsrfException.class);
        }
    }

    @Test
    void rejects_localhost_hostname() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://localhost:9090/actuator"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    void allows_publicLiteralIp() {
        assertThatCode(() -> SsrfGuard.assertAllowed("https://1.1.1.1/x")).doesNotThrowAnyException();
        assertThatCode(() -> SsrfGuard.assertAllowed("http://8.8.8.8/dns")).doesNotThrowAnyException();
    }

    @Test
    void isBlocked_flagsIpv4MappedLoopback() throws UnknownHostException {
        // ::ffff:127.0.0.1 must be treated as loopback, not public.
        InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
        org.junit.jupiter.api.Assertions.assertTrue(SsrfGuard.isBlocked("::ffff:127.0.0.1", mapped));
    }

    @Test
    void rejects_rangesOnlyTheFullRuleCovers() {
        // CGNAT, "this network", benchmarking, NAT64 and 6to4 slipped through
        // the old loopback/site-local check (planning/multi-tenant-isolation.md N3).
        for (String url : new String[] {
            "http://100.64.0.1/",
            "http://0.1.2.3/",
            "http://198.18.0.1/",
            "http://[64:ff9b::a00:1]/",
            "http://[2002:a00:1::1]/"
        }) {
            assertThatThrownBy(() -> SsrfGuard.assertAllowed(url)).as(url).isInstanceOf(SsrfGuard.SsrfException.class);
        }
    }

    @Test
    void privateNetworksAllow_opensPrivateRanges() {
        SsrfGuard.setPrivateNetworks(SsrfGuard.PrivateNetworks.ALLOW);

        assertThatCode(() -> SsrfGuard.assertAllowed("http://192.168.1.10/x")).doesNotThrowAnyException();
        assertThatCode(() -> SsrfGuard.assertAllowed("http://100.64.0.1/x")).doesNotThrowAnyException();
    }

    @Test
    void privateNetworksAllow_stillRefusesTheAlwaysBlockedRanges() {
        SsrfGuard.setPrivateNetworks(SsrfGuard.PrivateNetworks.ALLOW);

        for (String url : new String[] {
            "http://127.0.0.1:9990/actuator",
            "http://localhost/",
            "http://169.254.169.254/latest/meta-data",
            "http://0.0.0.0/",
            "http://255.255.255.255/",
            "http://[::1]/",
            "http://[::ffff:169.254.169.254]/",
            "http://[64:ff9b::7f00:1]/",
            "http://[2002:a9fe:a9fe::1]/"
        }) {
            assertThatThrownBy(() -> SsrfGuard.assertAllowed(url)).as(url).isInstanceOf(SsrfGuard.SsrfException.class);
        }
    }

    @Test
    void denyList_refusesListedRangeInEveryMode() {
        SsrfGuard.setPrivateNetworks(SsrfGuard.PrivateNetworks.ALLOW);
        SsrfGuard.setDenyList(EgressDenyList.parse(List.of("10.42.0.0/16")));

        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://10.42.40.23:27017/"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://[::ffff:10.42.40.23]/"))
                .isInstanceOf(SsrfGuard.SsrfException.class);
        assertThatCode(() -> SsrfGuard.assertAllowed("http://10.43.0.1/")).doesNotThrowAnyException();
    }

    @Test
    void denyList_hostName_isRefusedByName() {
        SsrfGuard.setPrivateNetworks(SsrfGuard.PrivateNetworks.ALLOW);
        SsrfGuard.setDenyList(EgressDenyList.parse(List.of("Mongo.Internal")));

        assertThat(SsrfGuard.isBlocked("mongo.internal", InetAddress.ofLiteral("10.1.2.3")))
                .isTrue();
        assertThat(SsrfGuard.isBlocked("other.internal", InetAddress.ofLiteral("10.1.2.3")))
                .isFalse();
    }

    @Test
    void rejects_malformedUrl() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://")).isInstanceOf(SsrfGuard.SsrfException.class);
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
        when(redirect.headers())
                .thenReturn(HttpHeaders.of(
                        Map.of("location", List.of("http://169.254.169.254/latest/meta-data/")), (a, b) -> true));
        when(client.send(any(), any())).thenReturn(redirect);

        HttpRequest head = HttpRequest.newBuilder(URI.create("https://1.1.1.1/x"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        assertThatThrownBy(() -> SsrfGuard.sendGuarded(client, head, HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    // ──────────────── capped body handler (OOM guard) ────────────────

    @Test
    void capped_abortsBodyOverLimit() throws Exception {
        HttpResponse.BodySubscriber<byte[]> sub = SsrfGuard.capped(HttpResponse.BodyHandlers.ofByteArray(), 10)
                .apply(mock(HttpResponse.ResponseInfo.class));
        java.util.concurrent.Flow.Subscription subscription = mock(java.util.concurrent.Flow.Subscription.class);
        sub.onSubscribe(subscription);

        sub.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[20]))); // 20 > 10

        org.mockito.Mockito.verify(subscription).cancel();
        assertThatThrownBy(() -> sub.getBody().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void capped_passesBodyUnderLimit() throws Exception {
        HttpResponse.BodySubscriber<byte[]> sub = SsrfGuard.capped(HttpResponse.BodyHandlers.ofByteArray(), 100)
                .apply(mock(HttpResponse.ResponseInfo.class));
        sub.onSubscribe(mock(java.util.concurrent.Flow.Subscription.class));

        sub.onNext(List.of(java.nio.ByteBuffer.wrap("hello".getBytes())));
        sub.onComplete();

        byte[] body = sub.getBody().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(new String(body)).isEqualTo("hello");
    }
}
