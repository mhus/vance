package de.mhus.vance.brain.ai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared HTTP+JSON plumbing for the per-provider
 * {@code listAvailableModels} implementations. Centralises the
 * HttpClient builder + timeout + JSON parse + error mapping so each
 * provider can focus on URL + auth + response-shape concerns.
 *
 * <p>The client is built lazily on first use and held statically —
 * Vance's discovery calls are infrequent, but pooling the connection
 * across providers keeps the cost off the hot path. Default timeout
 * of 30s is generous enough for cold endpoints (Ollama on first start)
 * but tight enough not to stall the whole discovery job on an
 * unreachable provider.
 *
 * <p>A second client carries the trust-all TLS context for instances
 * whose sidecar declares {@code tlsInsecure: true} (private-CA
 * gateways) — see {@link TlsInsecure}. Kept as a separate lazy holder
 * so validated-TLS instances never touch it and the two policies stay
 * structurally apart; redirect and timeout behaviour are identical to
 * the default client.
 */
public final class ProviderListingHttp {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final class InsecureHolder {
        static final HttpClient INSTANCE = buildInsecure();
    }

    private ProviderListingHttp() {}

    /**
     * GET + JSON-parse with validated TLS. Wraps every failure mode
     * (network, non-2xx, malformed JSON) into a single
     * {@link RuntimeException} the discovery service can catch with
     * one rule.
     */
    public static JsonNode fetchJson(HttpRequest request) {
        return fetchJson(request, false);
    }

    /**
     * {@link #fetchJson(HttpRequest)} with a per-instance TLS policy:
     * {@code insecureTls = true} sends the request through the
     * trust-all client (sidecar flag {@code tlsInsecure}, see
     * {@link TlsInsecure}).
     */
    @SuppressWarnings("PMD.CloseResource")
    public static JsonNode fetchJson(HttpRequest request, boolean insecureTls) {
        HttpClient client = insecureTls ? InsecureHolder.INSTANCE : CLIENT;
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("Provider listing failed " + response.statusCode() + " for " + request.uri()
                        + ": " + response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Provider listing interrupted for " + request.uri(), e);
        } catch (IOException | JacksonException e) {
            throw new RuntimeException("Provider listing IO error for " + request.uri() + ": " + e.getMessage(), e);
        }
    }

    private static HttpClient buildInsecure() {
        SSLContext ctx = TlsInsecure.trustAllContext();
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .sslContext(ctx)
                .build();
    }
}
