package de.mhus.vance.shared.oauthserver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
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
 */
@Service
@Slf4j
public class OAuthServerAuthorizationService {

    /** How long a request waits for login + consent. */
    public static final Duration REQUEST_TTL = Duration.ofMinutes(10);

    /** How long an issued code may be redeemed. */
    public static final Duration CODE_TTL = Duration.ofSeconds(60);

    /** How long a redeemed or expired code row stays around for replay detection. */
    static final Duration CODE_RETENTION = Duration.ofMinutes(10);

    private final OAuthServerAuthRequestRepository requests;
    private final OAuthServerCodeRepository codes;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    /** Outcome of {@link #redeemCode}. Exactly one of {@code code} / {@code replayedGrantId} is set, or neither. */
    public record Redemption(
            @Nullable OAuthServerCodeDocument code,
            @Nullable String replayedGrantId) {

        public boolean ok() {
            return code != null;
        }
    }

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
        request.setConsentTicket(null);
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
     * Bind the request to the user looking at it and hand out the consent
     * ticket. The first logged-in user wins; a different user later gets
     * {@link Optional#empty()} — a request link forwarded to someone else must
     * not become their consent.
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
                new Query(
                        Criteria.where("requestId").is(requestId).and("userId").is(null)),
                new Update().set("userId", userId).set("consentTicket", OAuthSecrets.random(24)),
                FindAndModifyOptions.options().returnNew(true),
                OAuthServerAuthRequestDocument.class);
        if (bound != null) {
            return Optional.of(bound);
        }
        // Lost a race against another bind — re-read and apply the same rule.
        return findRequest(tenantId, requestId).filter(r -> userId.equals(r.getUserId()));
    }

    /** Remove a request once it produced a code or was declined. */
    public void deleteRequest(String requestId) {
        requests.deleteByRequestId(requestId);
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
     * Redeem a code exactly once. The atomic stamp of {@code usedAt} is the
     * single-use guarantee; a code that is found but already stamped is a
     * replay and reported with its grant id so the caller can revoke it.
     * Expired codes are refused without being stamped.
     */
    public Redemption redeemCode(String tenantId, String rawCode) {
        String hash = OAuthSecrets.hash(rawCode);
        Instant now = clock.instant();
        OAuthServerCodeDocument before = mongoTemplate.findAndModify(
                new Query(Criteria.where("codeHash")
                        .is(hash)
                        .and("tenantId")
                        .is(tenantId)
                        .and("usedAt")
                        .is(null)
                        .and("expiresAt")
                        .gt(now)),
                new Update().set("usedAt", now),
                OAuthServerCodeDocument.class);
        if (before != null) {
            return new Redemption(before, null);
        }
        OAuthServerCodeDocument existing = codes.findByCodeHash(hash)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .orElse(null);
        if (existing != null && existing.getUsedAt() != null) {
            log.warn(
                    "OAuth authorization code replay tenant='{}' client='{}' grant='{}'",
                    tenantId,
                    existing.getClientId(),
                    existing.getGrantId());
            return new Redemption(null, existing.getGrantId());
        }
        return new Redemption(null, null);
    }
}
