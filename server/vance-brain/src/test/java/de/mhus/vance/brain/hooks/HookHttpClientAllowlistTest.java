package de.mhus.vance.brain.hooks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the SSRF allow-list. Network calls are NOT made — every
 * URL we expect to be blocked must throw before any
 * {@code HttpClient.send()} happens.
 */
class HookHttpClientAllowlistTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void post_blocksLoopback_byDefault() {
        HookHttpClient http = new HookHttpClient(
                httpClient, Duration.ofSeconds(2),
                /*allowPrivateNetworks*/ false,
                Set.of(), "hooktest");
        assertThatThrownBy(() -> http.post("http://127.0.0.1/x", null))
                .isInstanceOf(HookHttpClient.HookHttpException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void post_blocksRfc1918_byDefault() {
        HookHttpClient http = new HookHttpClient(
                httpClient, Duration.ofSeconds(2), false, Set.of(), "hooktest");
        assertThatThrownBy(() -> http.post("http://10.1.2.3/x", null))
                .isInstanceOf(HookHttpClient.HookHttpException.class)
                .hasMessageContaining("private");
    }

    @Test
    void post_blocksBrainPublicHost() {
        HookHttpClient http = new HookHttpClient(
                httpClient, Duration.ofSeconds(2), true,
                Set.of("brain.example.org"), "hooktest");
        assertThatThrownBy(() -> http.post("https://brain.example.org/anything", null))
                .isInstanceOf(HookHttpClient.HookHttpException.class)
                .hasMessageContaining("Brain host");
    }

    @Test
    void post_rejectsNonHttpScheme() {
        HookHttpClient http = new HookHttpClient(
                httpClient, Duration.ofSeconds(2), true, Set.of(), "hooktest");
        assertThatThrownBy(() -> http.post("file:///etc/passwd", null))
                .isInstanceOf(HookHttpClient.HookHttpException.class)
                .hasMessageContaining("http/https");
    }

    @Test
    void post_rejectsBlankUrl() {
        HookHttpClient http = new HookHttpClient(
                httpClient, Duration.ofSeconds(2), true, Set.of(), "hooktest");
        assertThatThrownBy(() -> http.post("", null))
                .isInstanceOf(HookHttpClient.HookHttpException.class)
                .hasMessageContaining("non-empty");
    }
}
