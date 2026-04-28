package de.mhus.vance.aitest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mints (and refreshes) a JWT for the brain REST API.
 *
 * <p>Hits {@code POST /brain/{tenant}/access/{username}} with the
 * configured password, parses the {@code AccessTokenResponse}
 * {@code {token, expiresAtTimestamp}}, and exposes the bearer token
 * for callers (e.g. {@link InboxAutoResponder}). Cheap to refresh —
 * just call {@link #mint()} again; tokens are minted server-side
 * with a long lifetime, so we don't bother with refresh-on-401.
 */
public final class BrainAuthClient {

    private final String baseUrl;
    private final String tenant;
    private final String username;
    private final String password;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile String token = "";

    public BrainAuthClient(String baseUrl, String tenant, String username, String password) {
        this.baseUrl = baseUrl;
        this.tenant = tenant;
        this.username = username;
        this.password = password;
    }

    /** Mint a fresh JWT. Stored internally; also returned for convenience. */
    public synchronized String mint() throws IOException, InterruptedException {
        String body = json.writeValueAsString(Map.of("password", password));
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(
                                baseUrl + "/brain/" + tenant + "/access/" + username))
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("JWT mint failed (HTTP " + r.statusCode()
                    + "): " + r.body());
        }
        Map<String, Object> parsed = json.readValue(r.body(), Map.class);
        Object t = parsed.get("token");
        if (t == null) {
            throw new IOException("JWT mint response missing 'token' field: " + r.body());
        }
        this.token = t.toString();
        return this.token;
    }

    /** Returns the cached token, minting one on first call. */
    public synchronized String token() throws IOException, InterruptedException {
        if (token.isEmpty()) {
            return mint();
        }
        return token;
    }

    public String tenant() { return tenant; }
    public String username() { return username; }
    public String baseUrl() { return baseUrl; }
}
