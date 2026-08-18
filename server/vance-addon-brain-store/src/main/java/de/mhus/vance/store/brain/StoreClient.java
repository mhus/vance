package de.mhus.vance.store.brain;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
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
            /** Null while a text waits for moderation — the star still counts. */
            @Nullable String text,
            @Nullable String version,
            @Nullable Integer majorVersion,
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
            @Nullable Integer licenseTermDays,
            /** What the vendor says the kit is for. */
            @Nullable List<String> topics,
            /** What its newest published version contains — derived at the store. */
            @Nullable List<String> contains) {}

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
            @Nullable String withdrawalNoticeVersion,
            String billingCountry, @Nullable String vatId) {

        String body = json.writeValueAsString(new OrderBody(
                vendor, kitId, billingCountry, vatId, withdrawalNoticeVersion));
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
            KitSourceDto source, String linkToken, String vendor, String kitId,
            int stars, @Nullable String text, @Nullable String version) {

        String body = json.writeValueAsString(new ReviewBody(stars, text, version));
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
            // Connect failures often carry no message at all, and "not
            // reachable: null" tells a reader nothing about whether the host
            // is down, the port is wrong or DNS failed. The class name is
            // not much, but it is the difference between a hint and noise.
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            throw new KitException("store '" + source.getId() + "' is not reachable: "
                    + reason, e);
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

    // ──────────────────── developer surface ────────────────────

    /** The wording somebody accepts to become a vendor. */
    public record VendorTerms(String version, String text) {}

    /** What a store keeps of a sale, so a vendor can see it before pricing. */
    public record Fees(double percent, long minimumFeeCents, long minimumPriceCents) {}

    /** A vendor profile with its standing. */
    public record Vendor(
            String name,
            String displayName,
            @Nullable String homepage,
            String status,
            @Nullable String termsVersion,
            @Nullable String rejectionReason) {}

    /** One step of a release request — this is where a refusal is read. */
    public record ReleaseRound(
            int no,
            @Nullable Instant at,
            String source,
            String verdict,
            @Nullable String actor,
            @Nullable String message) {}

    /** A release request with its whole proceeding. */
    public record ReleaseRequest(
            String requestId,
            String vendorName,
            String kitId,
            String version,
            String status,
            @Nullable Instant updatedAt,
            List<ReleaseRound> rounds) {}

    /** A release as the operator's queue shows it. */
    public record Release(
            String vendorName,
            String kitId,
            String version,
            String status,
            @Nullable Instant submittedAt,
            @Nullable String rejectionReason) {}

    /** Who a credential belongs to at a store, and whether it may operate there. */
    public record Identity(
            String accountId,
            @Nullable String displayName,
            String status,
            boolean operator,
            /** Has a vendor profile — the developer role, any status. */
            boolean vendor,
            String via) {}

    /**
     * Ask the store who this installation is acting as.
     *
     * <p>The operator role is the store's to know: it comes from that
     * service's own configuration, and a brain keeping its own claim about
     * it would be a second copy of a truth that lives elsewhere.
     */
    public Identity identity(KitSourceDto source, String linkToken) {
        return get(source, "/store/me", linkToken, Identity.class, "this account");
    }

    /** The terms and the fees — both unauthenticated, both shown before applying. */
    public VendorTerms vendorTerms(KitSourceDto source) {
        return get(source, "/store/vendor/terms", null, VendorTerms.class, "vendor terms");
    }

    public Fees fees(KitSourceDto source) {
        return get(source, "/store/vendor/fees", null, Fees.class, "store fees");
    }

    /**
     * Apply to be a vendor.
     *
     * <p>Takes a <b>session</b>, not a link: accepting terms is a decision
     * by a person, and a machine credential must not enter an agreement on
     * their behalf. Everything afterwards takes the link.
     */
    public Vendor applyVendor(
            KitSourceDto source, Session session,
            String name, String displayName, @Nullable String homepage, String termsVersion) {

        String body = json.writeValueAsString(
                new ApplyVendorBody(name, displayName, homepage, termsVersion));
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri(source, "/store/vendor"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + session.token())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), source);

        if (response.statusCode() == 409) {
            throw new KitException("the store refused the application: " + response.body());
        }
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " for the vendor application");
        }
        return read(response.body(), Vendor.class, source);
    }

    /**
     * Where a vendor stands on publishing.
     *
     * @param standing NOT_REQUIRED, VALID, GRACE or EXPIRED
     */
    public record Publishing(
            String vendorName,
            String standing,
            @Nullable Instant paidUntil,
            long renewalPriceCents,
            @Nullable String currency,
            boolean mayCreateKits,
            boolean mayPublishPaid) {}

    /** One answer per handle: the right is bought per shop front. */
    public List<Publishing> publishing(KitSourceDto source, String linkToken) {
        return getList(source, "/store/vendor/publishing", linkToken,
                Publishing.class, "publishing rights");
    }

    /**
     * Buy one more publishing period.
     *
     * <p>The session rather than the link token, like every other line that
     * spends money: a machine's credential does not enter agreements.
     */
    public Order renewPublishing(KitSourceDto source, Session session, String vendorName) {
        String body = json.writeValueAsString(new RenewBody(vendorName));
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri(source, "/store/vendor/publishing/renew"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + session.token())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), source);

        if (response.statusCode() == 400 || response.statusCode() == 409) {
            throw new KitException("the store refused the renewal: " + response.body());
        }
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " when renewing publishing for " + vendorName);
        }
        return read(response.body(), Order.class, source);
    }

    private record RenewBody(String vendorName) {}

    /** The vendor profiles this installation's account holds. */
    public List<Vendor> myVendors(KitSourceDto source, String linkToken) {
        return getList(source, "/store/vendor", linkToken, Vendor.class, "vendors");
    }

    public List<CatalogueEntry> myKits(KitSourceDto source, String linkToken) {
        return getList(source, "/store/vendor/kits", linkToken, CatalogueEntry.class, "kits");
    }

    public List<ReleaseRequest> myRequests(KitSourceDto source, String linkToken) {
        return getList(source, "/store/vendor/requests", linkToken,
                ReleaseRequest.class, "release requests");
    }

    /** Add a catalogue entry under one's own vendor. */
    public CatalogueEntry createKit(
            KitSourceDto source, String linkToken, String vendorName, String kitId,
            String displayName, @Nullable String description, long priceCents,
            @Nullable String currency, @Nullable List<String> topics) {

        String body = json.writeValueAsString(new CreateKitBody(
                vendorName, kitId, displayName, description, null, null,
                priceCents, currency, null, topics));
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri(source, "/store/vendor/kits"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + linkToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), source);

        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new KitException("the store refused the kit: " + describe(response));
        }
        return read(response.body(), CatalogueEntry.class, source);
    }

    /**
     * Upload a version of one's own kit.
     *
     * <p>Multipart, because that is what the store's endpoint takes and an
     * archive has no business being base64 in a JSON body. Built by hand:
     * the JDK client has no multipart publisher, and one file with one
     * field is not worth a dependency.
     */
    public ReleaseRequest uploadRelease(
            KitSourceDto source, String linkToken,
            String vendorName, String kitId, String version, Path archive) {

        String boundary = "vance-" + java.util.UUID.randomUUID();
        byte[] body;
        try {
            body = multipart(boundary, archive);
        } catch (IOException e) {
            throw new KitException("could not read the packed kit at " + archive, e);
        }
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri(source, "/store/vendor/kits/" + encode(vendorName) + "/"
                                + encode(kitId) + "/releases/" + encode(version)))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + linkToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(), source);

        if (response.statusCode() == 403) {
            throw new KitException("the store will not take this release yet: "
                    + describe(response));
        }
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " for the upload: " + describe(response));
        }
        return read(response.body(), ReleaseRequest.class, source);
    }

    private static byte[] multipart(String boundary, Path archive) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"archive\"; filename=\""
                + archive.getFileName() + "\"\r\n"
                + "Content-Type: application/zip\r\n\r\n";
        out.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(java.nio.file.Files.readAllBytes(archive));
        out.write(("\r\n--" + boundary + "--\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ──────────────────── operator surface ────────────────────

    /**
     * The operator calls take this installation's link, like reviewing.
     *
     * <p>Whether an account may operate is the store's answer, from its own
     * configuration — a link belonging to that account is that account
     * acting, and a second sign-in would establish nothing the store does
     * not already know. Asking for the password per approval would teach
     * people to type it into a brain screen instead.
     */
    public List<Vendor> pendingVendors(KitSourceDto source, String linkToken) {
        return getList(source, "/store/admin/vendors/pending", linkToken,
                Vendor.class, "pending vendors");
    }

    public List<Release> submittedReleases(KitSourceDto source, String linkToken) {
        return getList(source, "/store/admin/releases", linkToken,
                Release.class, "the release queue");
    }

    public void approveVendor(KitSourceDto source, String linkToken, String name) {
        post(source, linkToken, "/store/admin/vendors/" + encode(name) + "/approve",
                null, "approving vendor " + name);
    }

    public void rejectVendor(
            KitSourceDto source, String linkToken, String name, String reason) {
        post(source, linkToken, "/store/admin/vendors/" + encode(name) + "/reject",
                new RejectBody(reason), "refusing vendor " + name);
    }

    public void approveRelease(
            KitSourceDto source, String linkToken,
            String vendor, String kitId, String version) {
        post(source, linkToken, releasePath(vendor, kitId, version) + "/approve",
                null, "approving " + vendor + "/" + kitId + " " + version);
    }

    public void rejectRelease(
            KitSourceDto source, String linkToken,
            String vendor, String kitId, String version, String reason) {
        post(source, linkToken, releasePath(vendor, kitId, version) + "/reject",
                new RejectBody(reason), "refusing " + vendor + "/" + kitId + " " + version);
    }

    private static String releasePath(String vendor, String kitId, String version) {
        return "/store/admin/kits/" + encode(vendor) + "/" + encode(kitId)
                + "/releases/" + encode(version);
    }

    // ──────────────────── plumbing ────────────────────

    private <T> T get(
            KitSourceDto source, String path, @Nullable String token,
            Class<T> type, String what) {

        return read(body(source, path, token, what), type, source);
    }

    private <T> List<T> getList(
            KitSourceDto source, String path, @Nullable String token,
            Class<T> type, String what) {

        String body = body(source, path, token, what);
        try {
            return json.readValue(body, json.getTypeFactory()
                    .constructCollectionType(List.class, type));
        } catch (RuntimeException e) {
            throw new KitException("the store returned something that is not " + what, e);
        }
    }

    private String body(
            KitSourceDto source, String path, @Nullable String token, String what) {

        HttpRequest.Builder request = HttpRequest.newBuilder(uri(source, path))
                .timeout(TIMEOUT).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = send(request.build(), source);
        if (response.statusCode() == 401) {
            throw new KitException("the store no longer accepts this credential"
                    + " — sign in again");
        }
        if (response.statusCode() != 200) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " for " + what);
        }
        return response.body();
    }

    private void post(
            KitSourceDto source, String token, String path,
            @Nullable Object body, String what) {

        HttpRequest.Builder request = HttpRequest.newBuilder(uri(source, path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token);
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        HttpResponse<String> response = send(request.build(), source);
        if (response.statusCode() == 404) {
            // The operator surface answers 404 rather than 403 to an
            // account that is not an operator. Saying "not found" back
            // would send somebody looking for a typo.
            throw new KitException("this account may not do that at " + source.getId()
                    + ", or " + what + " no longer applies");
        }
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new KitException("the store returned HTTP " + response.statusCode()
                    + " when " + what + ": " + describe(response));
        }
    }

    /** A store error body, trimmed to something a person can read in a banner. */
    private static String describe(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) return "HTTP " + response.statusCode();
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    // Wire shapes, mirrored only as far as this addon needs them.
    private record LoginBody(String email, String password) {}

    private record ApplyVendorBody(
            String name, String displayName,
            @Nullable String homepage, String termsVersion) {}

    private record CreateKitBody(
            String vendorName, String kitId, String displayName,
            @Nullable String description, @Nullable String license, @Nullable String homepage,
            long priceCents, @Nullable String currency, @Nullable Integer licenseTermDays,
            @Nullable List<String> topics) {}

    private record RejectBody(String reason) {}

    private record AccountBody(String accountId, String email,
            @Nullable String displayName, String status) {}

    private record SessionBody(String token, @Nullable Instant expiresAt, AccountBody account) {}

    private record CreateLinkBody(
            @Nullable String label, @Nullable String tenantId, @Nullable String projectId) {}

    private record IssuedLinkBody(String linkId, String token, @Nullable String label) {}

    private record ReviewBody(
            int stars, @Nullable String text, @Nullable String version) {}

    private record OrderBody(
            String vendorName, String kitId, String billingCountry,
            @Nullable String vatId, @Nullable String withdrawalNoticeVersion) {}
}
