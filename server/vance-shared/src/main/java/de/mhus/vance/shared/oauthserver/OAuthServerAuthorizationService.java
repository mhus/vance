package de.mhus.vance.shared.oauthserver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Data owner of the two short-lived halves of the authorization code flow:
 * pending {@link OAuthServerAuthRequestDocument requests} and issued
 * {@link OAuthServerCodeDocument codes}.
 *
 * <p>Both are taken, not read-then-deleted: a request leaves the store in the
 * same atomic step that hands it to a decision, a code is stamped used in the
 * same step that redeems it. Everything before that step only reads.
 */
@Service
public class OAuthServerAuthorizationService {

    /** How long a request waits for login + consent. */
    public static final Duration REQUEST_TTL = Duration.ofMinutes(10);

    /** How long an issued code may be redeemed. */
    public static final Duration CODE_TTL = Duration.ofSeconds(60);

    /** How long a redeemed or expired code row stays around for replay detection. */
    static final Duration CODE_RETENTION = Duration.ofMinutes(10);

    /**
     * Longest {@code state} the authorization endpoint accepts. The value is
     * stored with the request and echoed into a URL; a client needs a random
     * nonce, not a document.
     */
    public static final int MAX_STATE_LENGTH = 1024;

    private final OAuthServerAuthRequestRepository requests;
    private final OAuthServerCodeRepository codes;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    @Autowired
    public OAuthServerAuthorizationService(
            OAuthServerAuthRequestRepository requests, OAuthServerCodeRepository codes, MongoTemplate mongoTemplate) {
        this(requests, codes, mongoTemplate, Clock.systemUTC());
    }

    /** Test seam: controllable clock. */
    OAuthServerAuthorizationService(
            OAuthServerAuthRequestRepository requests,
            OAuthServerCodeRepository codes,
            MongoTemplate mongoTemplate,
            Clock clock) {
        this.requests = requests;
        this.codes = codes;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /** Persist a validated request; stamps id, creation and expiry. */
    public OAuthServerAuthRequestDocument createRequest(OAuthServerAuthRequestDocument request) {
        Instant now = clock.instant();
        request.setId(null);
        request.setRequestId(OAuthSecrets.random(18));
        request.setUserId(null);
        request.setConsentTicketHash(null);
        request.setCreatedAt(now);
        request.setExpiresAt(now.plus(REQUEST_TTL));
        return requests.save(request);
    }

    /** The request if it exists in the tenant and has not expired. */
    public Optional<OAuthServerAuthRequestDocument> findRequest(String tenantId, String requestId) {
        Instant now = clock.instant();
        return requests.findByRequestId(requestId)
                .filter(r -> tenantId.equals(r.getTenantId()))
                .filter(r -> r.getExpiresAt().isAfter(now));
    }

    /**
     * Bind the request to the user looking at it. The first logged-in user
     * wins; a different user later gets {@link Optional#empty()} — a request
     * link forwarded to someone else must not become their consent.
     */
    public Optional<OAuthServerAuthRequestDocument> bindUser(String tenantId, String requestId, String userId) {
        OAuthServerAuthRequestDocument current =
                findRequest(tenantId, requestId).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        if (current.getUserId() != null) {
            return userId.equals(current.getUserId()) ? Optional.of(current) : Optional.empty();
        }
        OAuthServerAuthRequestDocument bound = mongoTemplate.findAndModify(
                new Query(Criteria.where("requestId")
                        .is(requestId)
                        .and("tenantId")
                        .is(tenantId)
                        .and("userId")
                        .is(null)),
                new Update().set("userId", userId),
                FindAndModifyOptions.options().returnNew(true),
                OAuthServerAuthRequestDocument.class);
        if (bound != null) {
            return Optional.of(bound);
        }
        // Lost a race against another bind — re-read and apply the same rule.
        return findRequest(tenantId, requestId).filter(r -> userId.equals(r.getUserId()));
    }

    /**
     * Hand the bound user a fresh consent ticket — the raw value, which exists
     * only in this return value; the request keeps its hash. A new ticket
     * replaces the previous one, so only the latest consent page can decide.
     *
     * @return the ticket, or empty when the request is gone or bound to someone else
     */
    public Optional<String> issueConsentTicket(String tenantId, String requestId, String userId) {
        String ticket = OAuthSecrets.random(24);
        OAuthServerAuthRequestDocument updated = mongoTemplate.findAndModify(
                liveRequestOf(tenantId, requestId, userId),
                new Update().set("consentTicketHash", OAuthSecrets.hash(ticket)),
                OAuthServerAuthRequestDocument.class);
        return updated == null ? Optional.empty() : Optional.of(ticket);
    }

    /**
     * Take the request for a consent decision: it must be bound to
     * {@code userId} and {@code consentTicket} must be its current ticket.
     * The request is removed in the same step, so two concurrent decisions
     * (a double-clicked button, a replayed POST) cannot both produce a code.
     *
     * @return the request, now removed — or empty when it does not line up or someone else took it
     */
    public Optional<OAuthServerAuthRequestDocument> takeForDecision(
            String tenantId, String requestId, String userId, String consentTicket) {
        OAuthServerAuthRequestDocument current =
                findRequest(tenantId, requestId).orElse(null);
        if (current == null
                || !userId.equals(current.getUserId())
                || current.getConsentTicketHash() == null
                || !OAuthSecrets.sameHash(current.getConsentTicketHash(), OAuthSecrets.hash(consentTicket))) {
            return Optional.empty();
        }
        // Conditional on the ticket hash just compared: a ticket re-issued in
        // between means a newer consent page holds the decision.
        Query query = liveRequestOf(tenantId, requestId, userId)
                .addCriteria(Criteria.where("consentTicketHash").is(current.getConsentTicketHash()));
        return Optional.ofNullable(mongoTemplate.findAndRemove(query, OAuthServerAuthRequestDocument.class));
    }

    /**
     * Take a bound request without a consent decision — the path where no
     * consent is asked (a reused third-party grant). Same single-winner
     * removal as {@link #takeForDecision}.
     */
    public Optional<OAuthServerAuthRequestDocument> take(String tenantId, String requestId, String userId) {
        return Optional.ofNullable(mongoTemplate.findAndRemove(
                liveRequestOf(tenantId, requestId, userId), OAuthServerAuthRequestDocument.class));
    }

    private Query liveRequestOf(String tenantId, String requestId, String userId) {
        return new Query(Criteria.where("requestId")
                .is(requestId)
                .and("tenantId")
                .is(tenantId)
                .and("userId")
                .is(userId)
                .and("expiresAt")
                .gt(clock.instant()));
    }

    /** Issue a code for a grant; returns the raw code — the only time it exists in clear. */
    public String issueCode(
            String tenantId,
            String userId,
            String grantId,
            String clientId,
            String redirectUri,
            String codeChallenge,
            @Nullable String resource) {
        String raw = OAuthSecrets.random(32);
        Instant now = clock.instant();
        codes.save(OAuthServerCodeDocument.builder()
                .codeHash(OAuthSecrets.hash(raw))
                .tenantId(tenantId)
                .userId(userId)
                .grantId(grantId)
                .clientId(clientId)
                .redirectUri(redirectUri)
                .codeChallenge(codeChallenge)
                .resource(resource)
                .expiresAt(now.plus(CODE_TTL))
                .purgeAt(now.plus(CODE_RETENTION))
                .build());
        return raw;
    }

    /**
     * Look a code up by its hash without spending it — used, unused, expired
     * alike. The caller checks the binding (client, redirect URI, PKCE,
     * resource) first and only then {@link #consumeCode consumes} it, so a
     * presenter that fails the binding neither spends the code nor triggers
     * replay handling.
     */
    public Optional<OAuthServerCodeDocument> findCode(String tenantId, String rawCode) {
        return codes.findByCodeHash(OAuthSecrets.hash(rawCode)).filter(c -> tenantId.equals(c.getTenantId()));
    }

    /** Whether the code is past its redemption window. */
    public boolean isExpired(OAuthServerCodeDocument code) {
        return !code.getExpiresAt().isAfter(clock.instant());
    }

    /**
     * Spend a code. The atomic stamp of {@code usedAt} — conditional on it
     * being unset and the code unexpired — is the single-use guarantee.
     *
     * @return true when this call spent it; false when it was already used
     *     (or expired) by the time the stamp ran
     */
    public boolean consumeCode(String tenantId, String rawCode) {
        Instant now = clock.instant();
        OAuthServerCodeDocument before = mongoTemplate.findAndModify(
                new Query(Criteria.where("codeHash")
                        .is(OAuthSecrets.hash(rawCode))
                        .and("tenantId")
                        .is(tenantId)
                        .and("usedAt")
                        .is(null)
                        .and("expiresAt")
                        .gt(now)),
                new Update().set("usedAt", now),
                OAuthServerCodeDocument.class);
        return before != null;
    }
}
