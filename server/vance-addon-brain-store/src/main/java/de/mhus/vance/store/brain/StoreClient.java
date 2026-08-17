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
 * <p>Deliberately narrow: sign in, mint a link, read the catalogue, read
 * and leave reviews. Everything a buyer already owns is answered by the
 * <em>delivery</em> service through the existing library path, and
 * everything installed is answered locally — so none of that appears here.
 *
 * <p>Spec: {@code planning/kit-store.md} §7 Phases S3 and S4.
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

    /** The score of one kit. */
    public record Score(double average, long count) {}

    /** One review as anyone browsing sees it — never with the author's account id. */
    public record Review(
            String reviewId,
            @Nullable String displayName,
            int stars,
            @Nullable String text,
            @Nullable Instant createdAt) {}

    /** One catalogue entry as the store presents it. */
    public record CatalogueEntry(
            String vendorName,
            String kitId,
            String displayName,
            @Nullable String description,
            @Nullable String license,
            @Nullable String homepage,
            @Nullable String version,
            @Nullable Instant publishedAt,
            @Nullable Score score,
            long priceCents,
            @Nullable String currency,
            @Nullable Integer licenseTermDays) {}

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

    /** What a buyer must agree to before a paid order is accepted. */
    public record WithdrawalNotice(boolean required, @Nullable String version) {}

    /**
     * The withdrawal notice currently in force at a store.
     *
     * <p>Fetched rather than configured here: the wording belongs to
     * whoever sells, and a brain that guessed the version would have its
     * orders refused — which is the correct outcome, but a confusing way
     * to discover it.
     */
    public WithdrawalNotice withdrawalNotice(KitSourceDto source) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                uri(source, "/store/orders/withdrawal-notice"))
                .timeout(TIMEOUT).GET().build(), source);
        if (response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " for its withdrawal notice");
        }
        return read(response.body(), WithdrawalNotice.class, source);
    }

    /** What an order came to. */
    public record Order(
            String orderId,
            String status,
            @Nullable String redirectUrl,
            @Nullable String failureReason) {}

    /**
     * Buy a kit.
     *
     * <p>Takes a <b>session</b> token, not a link: spending is a decision
     * about money and about the account, and the store only accepts a link
     * for leaving a review. Which is why this asks for the password again
     * — deliberately, and only here.
     */
    public Order order(
            KitSourceDto source, Session session, String vendor, String kitId,
            @Nullable String withdrawalNoticeVersion) {

        String body = json.writeValueAsString(
                new OrderBody(vendor, kitId, withdrawalNoticeVersion));
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri(source, "/store/orders"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + session.token())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), source);

        if (response.statusCode() == 403) {
            throw new KitException("this store account is not confirmed yet");
        }
        if (response.statusCode() == 400 || response.statusCode() == 409) {
            throw new KitException("the store refused the order: " + response.body());
        }
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " when ordering " + vendor + "/" + kitId);
        }
        return read(response.body(), Order.class, source);
    }

    /** The reviews of one kit whose text has been cleared. */
    public List<Review> reviews(KitSourceDto source, String vendor, String kitId) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                uri(source, "/store/catalogue/" + encode(vendor) + "/" + encode(kitId)
                        + "/ratings")).timeout(TIMEOUT).GET().build(), source);
        if (response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " for the reviews of " + vendor + "/" + kitId);
        }
        try {
            return json.readValue(response.body(), json.getTypeFactory()
                    .constructCollectionType(List.class, Review.class));
        } catch (RuntimeException e) {
            throw new KitException("the store returned something that is not a review list", e);
        }
    }

    /**
     * Leave or change a review, authenticated by this installation's link
     * token.
     *
     * <p>The link is the account's own agent here, and here is where the
     * kit is actually used. The store accepts it for this one endpoint and
     * for nothing else — a link cannot change a password or mint another
     * link, because those are decisions about the account itself.
     */
    public Review review(
            KitSourceDto source, String linkToken,
            String vendor, String kitId, int stars, @Nullable String text) {

        String body = json.writeValueAsString(new ReviewBody(stars, text));
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri(source, "/store/catalogue/" + encode(vendor) + "/"
                                + encode(kitId) + "/ratings"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + linkToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), source);

        if (response.statusCode() == 401) {
            throw new KitException("the store no longer accepts this installation's link"
                    + " — sign in again");
        }
        if (response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " when leaving a review");
        }
        return read(response.body(), Review.class, source);
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

    /** Path segments come from a catalogue; a stray slash must not reshape the request. */
    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
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

    private record ReviewBody(int stars, @Nullable String text) {}

    private record OrderBody(
            String vendorName, String kitId, @Nullable String withdrawalNoticeVersion) {}
}
