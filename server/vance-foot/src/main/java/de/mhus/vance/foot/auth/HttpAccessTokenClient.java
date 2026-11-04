package de.mhus.vance.foot.auth;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.access.EmailTokenStartResponse;
import de.mhus.vance.api.access.EmailTokenVerifyRequest;
import de.mhus.vance.api.access.LoginMethodsResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
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

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = JsonMapper.builder().build();

    @Override
    public AccessTokenResponse mint(String httpBase, String tenant, String username, AccessTokenRequest request)
            throws Exception {
        String body = post(
                httpBase + "/brain/" + tenant + "/access/" + username,
                json.writeValueAsString(request),
                "Token mint failed");
        return json.readValue(body, AccessTokenResponse.class);
    }

    @Override
    public @Nullable LoginMethodsResponse loginMethods(String httpBase, String tenant) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(
                            URI.create(httpBase + "/brain/" + tenant + "/login-methods"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            return json.readValue(response.body(), LoginMethodsResponse.class);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public EmailTokenStartResponse startEmailToken(String httpBase, String tenant, String username) throws Exception {
        String body = post(
                httpBase + "/brain/" + tenant + "/access/" + username + "/email-token",
                "",
                "Requesting the sign-in code failed");
        return json.readValue(body, EmailTokenStartResponse.class);
    }

    @Override
    public AccessTokenResponse verifyEmailToken(
            String httpBase, String tenant, String username, EmailTokenVerifyRequest request) throws Exception {
        String body = post(
                httpBase + "/brain/" + tenant + "/access/" + username + "/email-token/verify",
                json.writeValueAsString(request),
                "Sign-in code rejected");
        return json.readValue(body, AccessTokenResponse.class);
    }

    private String post(String url, String body, String failure) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new AccessTokenHttpException(
                    failure + ": HTTP " + response.statusCode()
                            + (response.body().isEmpty() ? "" : " — " + response.body()),
                    response.statusCode());
        }
        return response.body();
    }
}
