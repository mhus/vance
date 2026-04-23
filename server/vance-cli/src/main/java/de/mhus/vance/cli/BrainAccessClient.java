package de.mhus.vance.cli;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thin HTTP helper that mints a JWT against
 * {@code POST /brain/{tenant}/access/{username}}.
 */
class BrainAccessClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    AccessTokenResponse mint(String httpBase, String tenant, String username, String password) throws Exception {
        String url = httpBase + "/brain/" + tenant + "/access/" + username;
        String body = mapper.writeValueAsString(
                AccessTokenRequest.builder().password(password).build());

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Token mint failed: HTTP " + response.statusCode()
                            + (response.body().isEmpty() ? "" : " — " + response.body()));
        }
        return mapper.readValue(response.body(), AccessTokenResponse.class);
    }
}
