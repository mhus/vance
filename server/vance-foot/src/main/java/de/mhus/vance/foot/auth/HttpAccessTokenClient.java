package de.mhus.vance.foot.auth;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default {@link AccessTokenClient} — a plain JDK {@link HttpClient} POST
 * to {@code /brain/{tenant}/access/{username}}. This is the same call the
 * connection previously made inline; it now lives here so credential
 * acquisition is one concern.
 */
@Component
public class HttpAccessTokenClient implements AccessTokenClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = JsonMapper.builder().build();

    @Override
    public AccessTokenResponse mint(String httpBase, String tenant, String username,
                                    AccessTokenRequest request) throws Exception {
        String url = httpBase + "/brain/" + tenant + "/access/" + username;
        String body = json.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Token mint failed: HTTP " + response.statusCode()
                    + (response.body().isEmpty() ? "" : " — " + response.body()));
        }
        return json.readValue(response.body(), AccessTokenResponse.class);
    }
}
