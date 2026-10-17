package de.mhus.vance.addon.brain.contacts;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The only code that talks to Gravatar. Looks up the public avatar for an
 * e-mail address: SHA-256 of the trimmed, lower-cased address, {@code d=404}
 * so "no avatar" is an answer rather than a generated placeholder.
 *
 * <p>Called only on an explicit action (tool or button), never while listing
 * or importing: every lookup tells Gravatar that someone holds this address.
 */
@Component
@Slf4j
public class GravatarClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final HttpClient http;
    private final int maxBytes;

    @Autowired
    public GravatarClient(@Value("${vance.contacts.gravatar-url:https://gravatar.com/avatar/}") String baseUrl) {
        this(baseUrl, ContactPhotoProcessor.MAX_INPUT_BYTES);
    }

    GravatarClient(String baseUrl, int maxBytes) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.maxBytes = maxBytes;
        // No redirects: the answer comes from the configured host or not at all.
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * The avatar bytes, or empty when the address has none. The body is read
     * only for an {@code image/*} answer and only up to the size the picture
     * processor accepts anyway.
     */
    public Optional<byte[]> fetch(String email, int size) {
        URI uri = URI.create(baseUrl + hash(email) + "?s=" + size + "&d=404");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "image/*")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new GravatarUnavailableException("Gravatar is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GravatarUnavailableException("Gravatar lookup interrupted", e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) {
                throw new GravatarUnavailableException("Gravatar answered HTTP " + response.statusCode(), null);
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (!type.trim().toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new GravatarUnavailableException(
                        "Gravatar answered with '" + type + "' instead of a picture", null);
            }
            byte[] bytes = body.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new GravatarUnavailableException("Gravatar answered with an oversized picture", null);
            }
            log.trace("GravatarClient: avatar found ({} bytes)", bytes.length);
            return Optional.of(bytes);
        } catch (IOException e) {
            throw new GravatarUnavailableException("Gravatar answer could not be read: " + e.getMessage(), e);
        }
    }

    static String hash(String email) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
