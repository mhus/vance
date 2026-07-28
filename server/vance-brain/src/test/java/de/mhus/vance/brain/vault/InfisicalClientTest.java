package de.mhus.vance.brain.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.vault.VaultBinding;
import de.mhus.vance.shared.vault.VaultException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InfisicalClientTest {

    private static final LongSupplier FIXED_CLOCK = () -> 1_000L;

    private HttpClient http;
    private InfisicalClient client;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        client = new InfisicalClient(new ObjectMapper(), http, FIXED_CLOCK);
    }

    private static VaultBinding binding() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("clientId", "cid");
        cfg.put("project", "proj-1");
        cfg.put("environment", "prod");
        cfg.put("path", "/");
        return new VaultBinding("infisical", "https://vault.example.tld/", cfg, "csecret");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    @SafeVarargs
    private final void httpReturns(HttpResponse<String>... responses) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
    }

    private static final String LOGIN_OK = "{\"accessToken\":\"tok\",\"expiresIn\":3600}";

    @Test
    void readSecret_loginThenFetch_returnsValue() throws Exception {
        httpReturns(
                resp(200, LOGIN_OK),
                resp(200, "{\"secret\":{\"secretValue\":\"s3cr3t\"}}"));

        String value = client.readSecret(binding(), "jira-token");

        assertThat(value).isEqualTo("s3cr3t");
    }

    @Test
    void readSecret_missingSecret_returnsNull() throws Exception {
        httpReturns(resp(200, LOGIN_OK), resp(404, ""));

        assertThat(client.readSecret(binding(), "nope")).isNull();
    }

    @Test
    void readSecret_secondReadReusesCachedToken() throws Exception {
        httpReturns(
                resp(200, LOGIN_OK),
                resp(200, "{\"secret\":{\"secretValue\":\"a\"}}"),
                resp(200, "{\"secret\":{\"secretValue\":\"b\"}}"));

        assertThat(client.readSecret(binding(), "k1")).isEqualTo("a");
        assertThat(client.readSecret(binding(), "k2")).isEqualTo("b");

        // login (1) + two GETs (2) = 3 sends — no second login.
        verify(http, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void readSecret_staleCachedToken_refreshesAndRetriesOnce() throws Exception {
        httpReturns(
                resp(200, LOGIN_OK),                                    // read#1 login
                resp(200, "{\"secret\":{\"secretValue\":\"first\"}}"),  // read#1 GET ok -> caches token
                resp(401, "{\"message\":\"token expired\"}"),           // read#2 GET with cached token: stale
                resp(200, LOGIN_OK),                                    // read#2 forced re-login
                resp(200, "{\"secret\":{\"secretValue\":\"second\"}}")); // read#2 retry ok

        assertThat(client.readSecret(binding(), "k1")).isEqualTo("first");
        assertThat(client.readSecret(binding(), "k2")).isEqualTo("second");
        verify(http, times(5)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void readSecret_freshToken401_doesNotRetry() throws Exception {
        // A 401 on a token we just logged in for means the identity is rejected —
        // re-logging in would only repeat it, so no retry (fail fast).
        httpReturns(
                resp(200, LOGIN_OK),  // fresh login (cache empty)
                resp(401, "nope"));   // GET rejected on the fresh token

        assertThatThrownBy(() -> client.readSecret(binding(), "k"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("HTTP 401");
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void readSecret_serverError_throwsVaultException() throws Exception {
        httpReturns(resp(200, LOGIN_OK), resp(500, "boom"));

        assertThatThrownBy(() -> client.readSecret(binding(), "k"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void readSecret_missingProjectId_throwsBeforeAnyHttpCall() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("clientId", "cid");
        cfg.put("environment", "prod"); // no project
        VaultBinding incomplete = new VaultBinding("infisical", "https://x", cfg, "csecret");

        assertThatThrownBy(() -> client.readSecret(incomplete, "k"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("vault.project");
        verifyNoInteractions(http);
    }

    @Test
    void login_withoutClientSecret_throwsWithoutHttpCall() throws Exception {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("clientId", "cid");
        cfg.put("project", "proj-1");
        cfg.put("environment", "prod");
        VaultBinding noSecret = new VaultBinding("infisical", "https://x", cfg, null);

        assertThatThrownBy(() -> client.readSecret(noSecret, "k"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("vault.clientSecret");
        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void writeSecret_updatesThenCreatesWhenAbsent() throws Exception {
        httpReturns(
                resp(200, LOGIN_OK),  // login
                resp(404, ""),        // PATCH: secret does not exist
                resp(200, "{}"));     // POST: created

        client.writeSecret(binding(), "new-key", "value");

        verify(http, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
