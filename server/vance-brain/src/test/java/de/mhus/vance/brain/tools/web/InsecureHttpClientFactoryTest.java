package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

/**
 * Verifies the lazy singleton + custom SSL context wiring of
 * {@link InsecureHttpClientFactory}. The actual TLS handshake against
 * a broken-chain origin needs the real network and lives in an
 * integration test — here we only pin the shape of what the factory
 * hands out.
 */
class InsecureHttpClientFactoryTest {

    @Test
    void client_isSingleton() {
        HttpClient a = InsecureHttpClientFactory.client();
        HttpClient b = InsecureHttpClientFactory.client();

        assertThat(a).isSameAs(b);
    }

    @Test
    void client_carriesCustomSslContext() {
        // Default HttpClient builds its own SSLContext; ours injects a
        // trust-all one. The instances are not equal — confirms the
        // factory didn't quietly fall back to the JDK default.
        HttpClient insecure = InsecureHttpClientFactory.client();
        HttpClient defaultClient = HttpClient.newHttpClient();

        assertThat(insecure.sslContext()).isNotSameAs(defaultClient.sslContext());
    }

    @Test
    void client_neverAutoFollowsRedirects_soSsrfGuardReChecksEachHop() {
        HttpClient insecure = InsecureHttpClientFactory.client();

        // Security regression (code-review-2): the insecure (trust-all-TLS) client
        // is handed to SsrfGuard.sendGuarded, which MUST own redirect-following so
        // it re-checks each hop. A NORMAL client would let the JDK auto-follow a
        // public→private 3xx before the guard sees it, re-opening SSRF.
        assertThat(insecure.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(insecure.connectTimeout()).isPresent();
    }
}
