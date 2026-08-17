package de.mhus.vance.store.brain;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to a store service.
 *
 * <p>Server-side and nowhere else. The browser must never hold a store
 * credential — a session token in a page is one XSS away from someone
 * else's purchases, and a link token there would be one away from their
 * whole library.
 *
 * <p>Only three calls, and that is the point: sign in, mint a link, read
 * the catalogue. Everything a buyer already owns is answered by the
 * <em>delivery</em> service through the existing library path, and
 * everything installed is answered locally.
 *
 * <p>Spec: {@code planning/kit-store.md} §7 Phase S3.
 */
@Service
@Slf4j
public class StoreClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** A signed-in store session — held only long enough to mint a link. */
    public record Session(String token, String accountId, String email) {}

    /** A freshly minted link. The token is the thing that lands in settings. */
    public record IssuedLink(String linkId, String token) {}

    /** One catalogue entry as the store presents it. */
    public record CatalogueEntry(
            String vendorName,
            String kitId,
            String displayName,
            @Nullable String description,
            @Nullable String license,
            @Nullable String homepage,
            @Nullable String version,
            @Nullable Instant publishedAt) {}

    /**
     * Sign in.
     *
     * <p>The password is used here and discarded. It is never stored and
     * never reaches the browser again.
     */
    public Session login(KitSourceDto source, String email, String password) {
        String body = json.writeValueAsString(new LoginBody(email, password));
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri(source, "/store/session"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), source);

        if (response.statusCode() == 401) {
            throw new KitException("the store rejected those credentials");
        }
        if (response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " when signing in");
        }
        SessionBody parsed = read(response.body(), SessionBody.class, source);
        return new Session(parsed.token(), parsed.account().accountId(), parsed.account().email());
    }

    /**
     * Register this installation with the store and get its long-lived
     * token.
     *
     * <p>{@code tenantId} and {@code projectId} are sent so the person can
     * recognise the entry in their device list. The store treats them as a
     * label and nothing more — after S2 a delivery is bound to the account,
     * not to a tenant.
     */
    public IssuedLink createLink(
            KitSourceDto source, Session session,
            @Nullable String label, String tenantId, @Nullable String projectId) {

        String body = json.writeValueAsString(new CreateLinkBody(label, tenantId, projectId));
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri(source, "/store/links"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + session.token())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), source);

        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " when linking this installation");
        }
        IssuedLinkBody parsed = read(response.body(), IssuedLinkBody.class, source);
        return new IssuedLink(parsed.linkId(), parsed.token());
    }

    /** End the store session. Best effort — it expires on its own anyway. */
    public void logout(KitSourceDto source, Session session) {
        try {
            send(HttpRequest.newBuilder(uri(source, "/store/session"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + session.token())
                    .DELETE().build(), source);
        } catch (KitException e) {
            log.debug("StoreClient: could not end the store session: {}", e.getMessage());
        }
    }

    /**
     * What the store offers.
     *
     * <p>Unauthenticated: a shop window that demands a sign-in before it
     * shows what is for sale is a strange shop, and requiring one here
     * would mean this addon had to keep a store session.
     */
    public List<CatalogueEntry> catalogue(KitSourceDto source) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                uri(source, "/store/catalogue")).timeout(TIMEOUT).GET().build(), source);
        if (response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " for its catalogue");
        }
        try {
            return json.readValue(response.body(), json.getTypeFactory()
                    .constructCollectionType(List.class, CatalogueEntry.class));
        } catch (RuntimeException e) {
            throw new KitException("the store returned something that is not a catalogue", e);
        }
    }

    /**
     * Where this source's store front lives.
     *
     * <p>Falls back to the library url: delivery and store are two
     * processes but normally sit behind one hostname on different path
     * prefixes, so {@code storeUrl} stays unset for almost every
     * configuration.
     */
    static String storeBaseUrl(KitSourceDto source) {
        String base = source.getStoreUrl() == null || source.getStoreUrl().isBlank()
                ? source.getUrl()
                : source.getStoreUrl();
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    private static URI uri(KitSourceDto source, String path) {
        return URI.create(storeBaseUrl(source) + path);
    }

    private HttpResponse<String> send(HttpRequest request, KitSourceDto source) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new KitException("store '" + source.getId() + "' is not reachable: "
                    + e.getMessage(), e);
        }
    }

    private <T> T read(String body, Class<T> type, KitSourceDto source) {
        try {
            return json.readValue(body, type);
        } catch (RuntimeException e) {
            throw new KitException("store '" + source.getId()
                    + "' returned an unexpected answer", e);
        }
    }

    // Wire shapes, mirrored only as far as this addon needs them.
    private record LoginBody(String email, String password) {}

    private record AccountBody(String accountId, String email,
            @Nullable String displayName, String status) {}

    private record SessionBody(String token, @Nullable Instant expiresAt, AccountBody account) {}

    private record CreateLinkBody(
            @Nullable String label, @Nullable String tenantId, @Nullable String projectId) {}

    private record IssuedLinkBody(String linkId, String token, @Nullable String label) {}
}
