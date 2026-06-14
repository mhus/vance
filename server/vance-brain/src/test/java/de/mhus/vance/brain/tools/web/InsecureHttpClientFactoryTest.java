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
    void client_followsRedirects_andHasConnectTimeout() {
        HttpClient insecure = InsecureHttpClientFactory.client();

        assertThat(insecure.followRedirects()).isEqualTo(HttpClient.Redirect.NORMAL);
        assertThat(insecure.connectTimeout()).isPresent();
    }
}
